package com.decisionrail.config;

import com.decisionrail.api.ApiProblems;
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
                             @Value("${app.operations-password}") String operations) {
        for (String password : new String[]{demo, other, operations}) {
            if (password.length() < 16 || password.length() > 72 || password.contains("REPLACE")) {
                throw new IllegalArgumentException("Configure distinct 16-72 character merchant and operations passwords.");
            }
        }
        if (demo.equals(other) || demo.equals(operations) || other.equals(operations)) {
            throw new IllegalArgumentException("Each account must have a distinct password.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername("demo-merchant").password(encoder.encode(demo)).roles("MERCHANT").build(),
                User.withUsername("other-merchant").password(encoder.encode(other)).roles("MERCHANT").build(),
                User.withUsername("operations").password(encoder.encode(operations)).roles("OPERATIONS").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("OPERATIONS")
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
