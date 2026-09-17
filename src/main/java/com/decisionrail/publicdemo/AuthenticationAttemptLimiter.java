package com.decisionrail.publicdemo;

import com.decisionrail.api.ApiProblems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a client address that keeps failing to authenticate.
 *
 * <h2>What counts</h2>
 * Only what the authentication manager says. A failure is an {@link AbstractAuthenticationFailureEvent}
 * and a success is an {@link AuthenticationSuccessEvent}, both published by Spring Security for the
 * Basic chain and the browser sign-in alike, and both carrying the remote address the attempt came
 * from. Nothing is inferred from a response status: an earlier version of this filter counted any
 * sub-400 response to a Basic-bearing request as a success, and the browser chain deliberately ignores
 * Basic, so an anonymous {@code GET /ui/identity} with any {@code Authorization} header was a 200 that
 * authenticated nobody and wiped the address's count. That was reproduced against the real chains -
 * eight wrong administrator passwords, no refusal - and is what this design replaces.
 *
 * <h2>Whose failures a success clears</h2>
 * A success clears failures at that address <em>for that username only</em>. The visitor's password
 * is public, so authenticating as the visitor proves nothing about who is guessing the administrator's
 * password from the same address, and must not reset that count. An administrator who mistyped twice
 * and then got it right is cleared, which is the only case a reset is for.
 *
 * <h2>The gate</h2>
 * Before either chain runs, an attempt - a browser sign-in, or any request carrying Basic - from an
 * address whose failures within the window have reached the budget is answered 429 with
 * {@code Retry-After}, before any credential is examined. Requests that are not attempts pass through
 * untouched, whatever the address has done.
 *
 * <p>Behind the deployment's proxy the address is the proxied client, because the forwarded-header
 * strategy is on there and the proxy is the only thing able to reach this port and the only source of
 * the headers the strategy honours; the edge strips anything a client sends in them.
 */
public final class AuthenticationAttemptLimiter extends OncePerRequestFilter
        implements ApplicationListener<AbstractAuthenticationFailureEvent> {
    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final ObjectMapper mapper;
    /** Per address: the failures still inside the window, each with the username it was for. */
    private final Map<String, Deque<Failure>> failures = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    private record Failure(Instant at, String username) {}

    public AuthenticationAttemptLimiter(PublicDemoProperties properties, Clock clock, ObjectMapper mapper) {
        this.clock = clock;
        this.limit = properties.authFailuresPerWindow();
        this.window = properties.authFailureWindow();
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isAuthenticationAttempt(request)) {
            chain.doFilter(request, response);
            return;
        }
        String address = request.getRemoteAddr();
        long retryAfter = secondsUntilRelief(address);
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            ApiProblems.write(mapper, request, response, 429, "AUTHENTICATION_RATE_LIMITED",
                    "Too many failed sign-in attempts from this address. Wait and try again.");
            return;
        }
        chain.doFilter(request, response);
        if (sinceSweep.incrementAndGet() % 500 == 0) forgetIdle();
    }

    /** A real failure, as the authentication manager saw it. */
    @Override
    public void onApplicationEvent(AbstractAuthenticationFailureEvent event) {
        String address = addressOf(event.getAuthentication().getDetails());
        if (address == null) return;
        String username = String.valueOf(event.getAuthentication().getPrincipal());
        Deque<Failure> recent = failures.computeIfAbsent(address, k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent, clock.instant());
            recent.addLast(new Failure(clock.instant(), username));
        }
    }

    /** A real success: forgives that username's failures at that address, and no one else's. */
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String address = addressOf(event.getAuthentication().getDetails());
        if (address == null) return;
        String username = event.getAuthentication().getName();
        Deque<Failure> recent = failures.get(address);
        if (recent == null) return;
        synchronized (recent) {
            recent.removeIf(failure -> failure.username().equals(username));
        }
    }

    private static String addressOf(Object details) {
        return details instanceof WebAuthenticationDetails web ? web.getRemoteAddress() : null;
    }

    /** Zero when the address is within budget; otherwise seconds until its oldest failure ages out. */
    private long secondsUntilRelief(String address) {
        Deque<Failure> recent = failures.get(address);
        if (recent == null) return 0;
        synchronized (recent) {
            Instant now = clock.instant();
            prune(recent, now);
            if (recent.size() < limit) return 0;
            Failure oldest = recent.peekFirst();
            return oldest == null ? 0 : Math.max(1, Duration.between(now, oldest.at().plus(window)).toSeconds());
        }
    }

    private void prune(Deque<Failure> recent, Instant now) {
        Instant cutoff = now.minus(window);
        while (!recent.isEmpty() && !recent.peekFirst().at().isAfter(cutoff)) recent.pollFirst();
    }

    /** Bounded memory: an address with nothing left in its window is forgotten. */
    private void forgetIdle() {
        Instant cutoff = clock.instant().minus(window);
        failures.entrySet().removeIf(entry -> {
            Deque<Failure> recent = entry.getValue();
            synchronized (recent) {
                Failure newest = recent.peekLast();
                return newest == null || !newest.at().isAfter(cutoff);
            }
        });
    }

    private static boolean isAuthenticationAttempt(HttpServletRequest request) {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/ui/session".equals(request.getRequestURI())) return true;
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }
}
