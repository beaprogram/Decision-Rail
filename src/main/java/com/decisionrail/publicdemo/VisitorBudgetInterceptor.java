package com.decisionrail.publicdemo;

import com.decisionrail.api.ApiProblems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *   <li><b>Replay</b> - a background job over the visitor's history. Bounded per hour and to one in
 *       flight, because it is the one thing a visitor can start that keeps running after the request
 *       returns.</li>
 *   <li><b>Reconciliation</b> - a report that walks every account's whole history in one snapshot.
 *       Bounded per minute; the history itself is bounded elsewhere.</li>
 * </ul>
 * Everyone else is untouched: the private identities the operator walkthrough uses are not budgeted.
 */
public final class VisitorBudgetInterceptor implements HandlerInterceptor {
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final PublicDemoProperties properties;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final SlidingWindowBudget commands;
    private final SlidingWindowBudget replays;
    private final SlidingWindowBudget reconciliations;

    public VisitorBudgetInterceptor(PublicDemoProperties properties, Clock clock, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.properties = properties;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.commands = new SlidingWindowBudget(clock, properties.commandsPerMinute(), Duration.ofMinutes(1));
        this.replays = new SlidingWindowBudget(clock, properties.replayJobsPerHour(), Duration.ofHours(1));
        this.reconciliations = new SlidingWindowBudget(clock, properties.reconciliationPerMinute(), Duration.ofMinutes(1));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !properties.isVisitor(authentication.getName())) return true;
        String visitor = authentication.getName();
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.endsWith("/replay-jobs") && "POST".equals(method)) {
            if (runningReplayJobs(visitor) >= properties.maxRunningReplayJobs()) {
                return refuse(request, response, 30,
                        "The demo visitor already has a replay job running. Wait for it to finish, then start another.");
            }
            if (!replays.tryAcquire(visitor)) {
                return refuse(request, response, replays.secondsUntilRelief(visitor),
                        "The demo visitor has started its hourly allowance of %d replay jobs. Existing jobs and their reports stay readable."
                                .formatted(properties.replayJobsPerHour()));
            }
        }
        if (path.endsWith("/reconciliation") && "GET".equals(method) && !reconciliations.tryAcquire(visitor)) {
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

    private int runningReplayJobs(String merchant) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM replay_jobs WHERE merchant_id = ? AND status IN ('PENDING', 'RUNNING')",
                Integer.class, merchant);
        return count == null ? 0 : count;
    }

    private boolean refuse(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds, String detail)
            throws java.io.IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        ApiProblems.write(mapper, request, response, 429, "DEMO_CAPACITY_EXHAUSTED", detail);
        return false;
    }
}
