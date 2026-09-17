package com.decisionrail.publicdemo;

import com.decisionrail.api.ApiProblems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a client address that keeps failing to authenticate.
 *
 * <p>One rule for both chains. A browser sign-in is {@code POST /ui/session}; an API attempt is any
 * request carrying an {@code Authorization} header. Either that ends in 401 counts against the client
 * address, and once the window's budget is spent every further attempt from that address is answered
 * 429 with {@code Retry-After}, before any credential is examined.
 *
 * <p>The address is whatever the container reports as the remote address. Behind the deployment's
 * reverse proxy that is the proxied client, because the forwarded-header strategy is enabled there and
 * the proxy is the only thing able to reach the application; the application port is not published.
 * A successful authentication clears the address's count, so a mistyped password followed by a correct
 * one is not held against anyone.
 *
 * <p>The visitor's password is public, so this does nothing to protect that account and is not meant
 * to. What it protects is every other identity on the instance from being guessed at speed.
 */
public final class AuthenticationAttemptLimiter extends OncePerRequestFilter {
    private final SlidingWindowBudget failures;
    private final ObjectMapper mapper;
    private final AtomicInteger sinceSweep = new AtomicInteger();

    public AuthenticationAttemptLimiter(PublicDemoProperties properties, Clock clock, ObjectMapper mapper) {
        this.failures = new SlidingWindowBudget(clock, properties.authFailuresPerWindow(), properties.authFailureWindow());
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
        if (failures.exhausted(address)) {
            response.setHeader("Retry-After", Long.toString(failures.secondsUntilRelief(address)));
            ApiProblems.write(mapper, request, response, 429, "AUTHENTICATION_RATE_LIMITED",
                    "Too many failed sign-in attempts from this address. Wait and try again.");
            return;
        }
        chain.doFilter(request, response);
        if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
            failures.record(address);
        } else if (response.getStatus() < 400) {
            failures.clear(address);
        }
        if (sinceSweep.incrementAndGet() % 500 == 0) failures.forgetIdle();
    }

    private static boolean isAuthenticationAttempt(HttpServletRequest request) {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/ui/session".equals(request.getRequestURI())) return true;
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }
}
