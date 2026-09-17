package com.decisionrail.publicdemo;

import com.decisionrail.api.ApiProblems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Budgets for the shared public visitor, enforced before a handler runs.
 *
 * <p>An interceptor rather than a security rule, because it runs after both security chains have
 * established who is calling and applies to the controllers the same way whichever chain the request
 * came through. Refusing here, before the controller, is what makes a refusal clean: nothing has been
 * claimed, written or committed, so there is no partial work to discard and the caller can simply try
 * again later. That is the difference between a limit and a timeout.
 *
 * <p>Three budgets, each for a distinct cost:
 * <ul>
 *   <li><b>Commands</b> - every state-changing request. Bounds how fast synthetic history grows.</li>
 *   <li><b>Replay</b> - bounded per hour and to one in flight, but not here: those limits are decided
 *       atomically inside the job-creation transaction by {@link VisitorReplayAdmission}.</li>
 *   <li><b>Reconciliation</b> - a report that walks every account's whole history in one snapshot.
 *       Bounded per minute; the history itself is bounded elsewhere.</li>
 * </ul>
 * Everyone else is untouched: the private identities the operator walkthrough uses are not budgeted.
 */
public final class VisitorBudgetInterceptor implements HandlerInterceptor {
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final PublicDemoProperties properties;
    private final ObjectMapper mapper;
    private final SlidingWindowBudget commands;
    private final SlidingWindowBudget reconciliations;

    public VisitorBudgetInterceptor(PublicDemoProperties properties, Clock clock, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.commands = new SlidingWindowBudget(clock, properties.commandsPerMinute(), Duration.ofMinutes(1));
        this.reconciliations = new SlidingWindowBudget(clock, properties.reconciliationPerMinute(), Duration.ofMinutes(1));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !properties.isVisitor(authentication.getName())) return true;
        String visitor = authentication.getName();
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Replay limits - in flight and per hour - are not decided here. Counting rows before the
        // controller creates one is a race between concurrent requests; they are decided inside the
        // job-creation transaction by VisitorReplayAdmission, under a lock, where the count and the
        // creation cannot be separated. A replay request still spends a command below.
        // HEAD dispatches to the GET handler with only the body dropped, so the report is computed
        // either way; the budget is about the computation and is charged for both.
        if (path.endsWith("/reconciliation") && ("GET".equals(method) || "HEAD".equals(method))
                && !reconciliations.tryAcquire(visitor)) {
            return refuse(request, response, reconciliations.secondsUntilRelief(visitor),
                    "Reconciliation is limited to %d reports a minute for the demo visitor.".formatted(properties.reconciliationPerMinute()));
        }
        if (MUTATIONS.contains(method) && !path.equals("/ui/session") && !commands.tryAcquire(visitor)) {
            return refuse(request, response, commands.secondsUntilRelief(visitor),
                    "The demo visitor has used its allowance of %d commands a minute. Nothing was changed by this request; try again shortly."
                            .formatted(properties.commandsPerMinute()));
        }
        return true;
    }

    private boolean refuse(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds, String detail)
            throws java.io.IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        ApiProblems.write(mapper, request, response, 429, "DEMO_CAPACITY_EXHAUSTED", detail);
        return false;
    }
}
