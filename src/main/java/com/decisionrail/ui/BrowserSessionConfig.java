package com.decisionrail.ui;

import com.decisionrail.api.ApiProblems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security for the browser dashboard: static assets, and a session-authenticated JSON API under
 * {@code /ui/**}.
 *
 * <h2>Why a separate chain rather than adding cookies to the existing API</h2>
 * The {@code /v1/**} API is stateless Basic auth with CSRF disabled, which is correct for it: a request
 * carrying an {@code Authorization} header cannot be forged by another origin, because a browser will
 * not attach that header cross-site. A session cookie is the opposite — browsers attach it
 * automatically — so the moment cookies authenticate an endpoint, CSRF protection becomes mandatory.
 *
 * <p>Rather than make one chain behave two ways and reason about which requests are cookie-authenticated,
 * the browser API lives on its own path prefix with its own chain. That keeps each chain's rules
 * readable in one place, leaves the existing API's behaviour completely unchanged, and means a session
 * cookie cannot reach {@code /v1/**} at all: a browser session is not an alternative way into the
 * stateless API, and the chains cannot silently borrow each other's protections.
 *
 * <h2>Chain order</h2>
 * Static assets are matched first, the browser API second, and everything else falls through to the
 * existing API chain, whose {@code denyAll} default still covers every unlisted path.
 */
@Configuration
public class BrowserSessionConfig {

    /**
     * Dashboard assets: the compiled bundle and the single page that loads it.
     *
     * <p>Publicly readable on purpose. It contains no credentials and no tenant data; authentication
     * happens when the page calls {@code /ui/**}. Only this prefix is opened, so the API chain's
     * deny-by-default still governs everything else.
     */
    @Bean
    @Order(0)
    SecurityFilterChain dashboardAssets(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/", "/index.html", "/favicon.ico", "/dashboard/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * The browser API.
     *
     * <p>Authorisation mirrors the existing API's discipline exactly: privileged routes are listed
     * before the broad merchant rule so an administrative path can never fall through and be authorised
     * as an ordinary merchant call, and the chain denies anything it does not name.
     *
     * <p>OPERATIONS appears nowhere below. It remains the metrics-only identity; it can sign in and is
     * told it has no dashboard capabilities, which is not the same as being given any.
     */
    @Bean
    @Order(1)
    SecurityFilterChain browserApi(HttpSecurity http, ObjectMapper mapper,
                                   @org.springframework.beans.factory.annotation.Value("${app.ui.secure-cookies:false}") boolean secureCookies)
            throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // The page must read this cookie to echo the token back in a header, so it cannot be HttpOnly.
        // That is safe: the token's purpose is to prove the request came from our own page, not to
        // authenticate. Authentication stays in the HttpOnly session cookie, which script cannot read.
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(secureCookies).sameSite("Lax").path("/"));

        return http
                .securityMatcher("/ui/**")
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                // Ensures the token is actually materialised and sent, rather than deferred until
                // something reads it. A single-page app fetches the token before its first mutation,
                // so it has to be present on an ordinary GET.
                .addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)
                // No CORS: the dashboard is served from the same origin as this API. Enabling it would
                // be the only way a credentialed cross-origin request could succeed, so it stays off.
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // A new identity gets a new session id, so a fixed pre-login id cannot be
                        // promoted to an authenticated one.
                        .sessionFixation(fixation -> fixation.newSession()))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        // Bootstrap: tells the page who it is, if anyone, and carries the CSRF cookie.
                        .requestMatchers(HttpMethod.GET, "/ui/identity").permitAll()
                        .requestMatchers(HttpMethod.POST, "/ui/session").permitAll()
                        .requestMatchers("/ui/ops/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/ui/policies").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/ui/policies", "/ui/policies/*").hasAnyRole("MERCHANT", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/ui/session").authenticated()
                        .requestMatchers("/ui/**").hasRole("MERCHANT")
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        // Spring's own login filter, so session fixation protection and CSRF token
                        // rotation on authentication are handled by the framework rather than by hand.
                        .loginProcessingUrl("/ui/session")
                        .successHandler(new IdentityResponseHandler(mapper))
                        .failureHandler((request, response, failure) -> ApiProblems.write(mapper, request, response,
                                401, "AUTHENTICATION_FAILED", "The username or password is not correct."))
                        .permitAll())
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/ui/session"))
                        // A real server-side logout: the session is destroyed, not just forgotten by
                        // the client, so a copied cookie cannot be replayed afterwards.
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new SignedOutResponseHandler(csrfTokens)))
                .exceptionHandling(errors -> errors
                        // JSON, never a redirect to a login page: the caller is always script.
                        .authenticationEntryPoint((request, response, exception) -> ApiProblems.write(mapper, request,
                                response, 401, "AUTHENTICATION_REQUIRED", "Sign in to continue."))
                        .accessDeniedHandler((request, response, exception) -> {
                            boolean csrf = exception instanceof org.springframework.security.web.csrf.CsrfException;
                            ApiProblems.write(mapper, request, response, 403,
                                    csrf ? "CSRF_TOKEN_INVALID" : "ACCESS_DENIED",
                                    csrf ? "The request could not be verified as coming from the dashboard. Reload and try again."
                                            : "This action is not permitted for your role.");
                        }))
                .build();
    }

    /**
     * Writes the authenticated identity as the login response, so the page learns its role and
     * capabilities from the same round trip that signed it in.
     *
     * <p>It also forces the rotated CSRF token onto the response. Authentication deletes the old token
     * and defers writing its replacement until something asks for it, and a successful login short
     * circuits the filter chain, so nothing downstream ever asked. The page was therefore left holding
     * no token at all, and its next state-changing request, including signing out again, was refused.
     * Materialising it here is what makes the response self-sufficient rather than leaving the page to
     * discover a token from some unrelated request it might never make.
     */
    private record IdentityResponseHandler(ObjectMapper mapper)
            implements org.springframework.security.web.authentication.AuthenticationSuccessHandler {
        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            org.springframework.security.core.Authentication authentication)
                throws IOException {
            materialiseCsrfToken(request);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            // Authenticated identity is never cacheable by a proxy or the browser.
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getWriter(), IdentityView.of(authentication));
        }
    }

    /**
     * Confirms the sign-out and hands back a fresh CSRF token for the sign-in form that follows.
     *
     * <p>Logging out invalidates the session and with it the old token, and the sign-in screen makes no
     * other requests, so without this the very next sign-in would arrive with nothing to verify it and
     * be refused. The token is not a credential: it identifies our own page, and issuing one to an
     * anonymous caller is what lets that caller authenticate at all.
     */
    private record SignedOutResponseHandler(CookieCsrfTokenRepository csrfTokens)
            implements org.springframework.security.web.authentication.logout.LogoutSuccessHandler {
        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                    org.springframework.security.core.Authentication authentication) {
            csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    /**
     * Resolves the deferred CSRF token so the repository actually writes it to the response.
     *
     * <p>Spring stores a supplier in the request attribute and only saves the token when something
     * reads it. Reading it here is the documented way to force that write.
     */
    private static void materialiseCsrfToken(HttpServletRequest request) {
        Object deferred = request.getAttribute(CsrfToken.class.getName());
        if (deferred instanceof CsrfToken token) {
            token.getToken();
        }
    }

    /**
     * Resolves the CSRF token on every browser-API request so the cookie is present before the page
     * needs it. Without this the token is created lazily and a first mutation would have nothing to
     * send.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * The request handler pattern Spring Security documents for single-page applications.
     *
     * <p>Tokens are masked per response when rendered into the cookie, which is what defeats a BREACH
     * oracle, but a token arriving in a header has already been read from that cookie by script and is
     * therefore the raw value. Delegating only the header case to the plain handler is what makes both
     * halves line up.
     */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final XorCsrfTokenRequestAttributeHandler masked = new XorCsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           java.util.function.Supplier<CsrfToken> token) {
            masked.handle(request, response, token);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
            // A header means the page read the cookie, so the value is unmasked. Anything else is a
            // form post, whose value was rendered masked.
            return request.getHeader(token.getHeaderName()) != null
                    ? plain.resolveCsrfTokenValue(request, token)
                    : masked.resolveCsrfTokenValue(request, token);
        }
    }
}
