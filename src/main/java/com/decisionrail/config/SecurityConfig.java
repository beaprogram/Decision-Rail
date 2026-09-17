package com.decisionrail.config;

import com.decisionrail.api.ApiProblems;
import com.decisionrail.publicdemo.PublicDemoProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService users(PasswordEncoder encoder, @Value("${app.merchant-demo-password}") String demo,
                             @Value("${app.merchant-other-password}") String other,
                             @Value("${app.operations-password}") String operations,
                             @Value("${app.admin-password}") String admin,
                             PublicDemoProperties publicDemo) {
        java.util.List<String> passwords = new java.util.ArrayList<>(java.util.List.of(demo, other, operations, admin));
        if (publicDemo.enabled()) passwords.add(publicDemo.visitorPassword());
        for (String password : passwords) {
            if (password == null || password.length() < 16 || password.length() > 72 || password.contains("REPLACE")) {
                throw new IllegalArgumentException("Configure distinct 16-72 character merchant, operations and administrator passwords.");
            }
        }
        if (new java.util.HashSet<>(passwords).size() != passwords.size()) {
            throw new IllegalArgumentException("Each account must have a distinct password.");
        }
        // OPERATIONS stays metrics-only. Administrative authority over policy creation, shadow
        // configuration and outbox redrive is a separate identity, deliberately not granted by
        // widening the existing metrics account.
        java.util.List<org.springframework.security.core.userdetails.UserDetails> identities = new java.util.ArrayList<>(java.util.List.of(
                User.withUsername("demo-merchant").password(encoder.encode(demo)).roles("MERCHANT").build(),
                User.withUsername("other-merchant").password(encoder.encode(other)).roles("MERCHANT").build(),
                User.withUsername("operations").password(encoder.encode(operations)).roles("OPERATIONS").build(),
                User.withUsername("admin").password(encoder.encode(admin)).roles("ADMIN").build()));
        if (publicDemo.enabled()) {
            // The shared public visitor. An ordinary MERCHANT to every ownership rule - it sees only
            // its own accounts and payments - and the only identity the public-demo budgets apply to.
            // Its password is public by design; the budgets and the authentication limiter are what
            // keep a publicly reachable instance bounded, not secrecy of this credential.
            if (identities.stream().anyMatch(u -> u.getUsername().equals(publicDemo.visitorUsername()))) {
                throw new IllegalArgumentException("The public-demo visitor username collides with a private identity.");
            }
            identities.add(User.withUsername(publicDemo.visitorUsername())
                    .password(encoder.encode(publicDemo.visitorPassword())).roles("MERCHANT").build());
        }
        return new InMemoryUserDetailsManager(identities);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper, PublicDemoProperties publicDemo) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                // Matcher order is significant: the first match wins. Every privileged route is
                // listed before the broad merchant rule, so an administrative path can never
                // fall through to "/v1/**" and be authorised as an ordinary merchant call.
                .authorizeHttpRequests(auth -> auth
                        // Liveness and readiness are what a host probes, and with show-details off they
                        // say only UP or DOWN. The async group deliberately shows its details - breaker
                        // state, backlog, failed counts - which is operator information: on a public
                        // instance it is read with an operator credential, not by anyone who finds the
                        // path. Listed before the broad health rule so it cannot fall through to it.
                        // The group and everything under it: Boot also serves a component at
                        // /actuator/health/async/<indicator>, which the exact-path rule used to leave
                        // to the wildcard below. Listed as the group plus its descendants.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/async", "/actuator/health/async/**")
                                .access(publicDemo.enabled()
                                        ? org.springframework.security.authorization.AuthorityAuthorizationManager.hasAnyRole("OPERATIONS", "ADMIN")
                                        : (authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(true))
                        // Only the intended probes are public. Any other health path - a component
                        // under readiness, an indicator by name - is not, on either kind of instance.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**")
                                .access(publicDemo.enabled()
                                        ? org.springframework.security.authorization.AuthorityAuthorizationManager.hasAnyRole("OPERATIONS", "ADMIN")
                                        : (authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(true))
                        // Which commit and image this is. Public so a visitor can match what they see to
                        // the release; it carries no configuration.
                        .requestMatchers(HttpMethod.GET, "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("OPERATIONS")
                        .requestMatchers("/v1/ops/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/policies").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/policies", "/v1/policies/*").hasAnyRole("MERCHANT", "ADMIN")
                        .requestMatchers("/v1/**").hasRole("MERCHANT")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) -> {
                    response.setHeader("WWW-Authenticate", "Basic realm=\"DecisionRail\"");
                    ApiProblems.write(mapper, request, response, 401, "AUTHENTICATION_REQUIRED", "Valid credentials are required.");
                }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                        ApiProblems.write(mapper, request, response, 403, "ACCESS_DENIED", "This action is not permitted.")))
                .build();
    }
}
