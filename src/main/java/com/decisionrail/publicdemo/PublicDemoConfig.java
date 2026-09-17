package com.decisionrail.publicdemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the public-demo budgets, and refuses to start a public instance that is misconfigured.
 *
 * <p>The refusals are the important part. A publicly reachable instance with fault injection
 * switchable, or with session cookies that a plain-HTTP hop could read, is not a configuration to
 * warn about and continue with; it is one to stop on, at startup, where the operator is looking.
 */
@Configuration
@EnableConfigurationProperties(PublicDemoProperties.class)
public class PublicDemoConfig {

    /**
     * Guards evaluated whenever the public demo is enabled. Everything here is a property the
     * deployment sets deliberately, so a failure names the property to change.
     */
    @Bean
    @ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "true")
    PublicDemoGuards publicDemoGuards(PublicDemoProperties properties,
                                      @Value("${app.events.fault-injection-enabled:false}") boolean faultInjection,
                                      @Value("${app.ui.secure-cookies:false}") boolean secureCookies) {
        if (faultInjection) {
            throw new IllegalStateException("app.public-demo.enabled is true but app.events.fault-injection-enabled is also true. "
                    + "Fault injection never runs on a public instance; unset EVENTS_FAULT_INJECTION_ENABLED.");
        }
        if (!secureCookies) {
            throw new IllegalStateException("app.public-demo.enabled is true but session cookies are not marked Secure. "
                    + "A public instance is served over HTTPS; set SESSION_COOKIE_SECURE=true.");
        }
        String password = properties.visitorPassword();
        if (password == null || password.length() < 16 || password.length() > 72 || password.contains("REPLACE")) {
            throw new IllegalStateException("app.public-demo.enabled is true but VISITOR_PASSWORD is not a 16-72 character value.");
        }
        return new PublicDemoGuards();
    }

    /** Marker bean: its existence means the guards above passed. */
    public static final class PublicDemoGuards {}

    @Bean
    @ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "true")
    WebMvcConfigurer visitorBudgets(PublicDemoProperties properties, Clock clock, ObjectMapper mapper, JdbcTemplate jdbc) {
        VisitorBudgetInterceptor interceptor = new VisitorBudgetInterceptor(properties, clock, mapper, jdbc);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/v1/**", "/ui/**");
            }
        };
    }

    /**
     * Runs just ahead of the security filter chain (which Spring Boot registers at -100), so a locked
     * address is refused before any credential is examined, and after the forwarded-header filter,
     * so the address it sees behind the proxy is the client's.
     */
    @Bean
    @ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "true")
    FilterRegistrationBean<AuthenticationAttemptLimiter> authenticationAttemptLimiter(
            PublicDemoProperties properties, Clock clock, ObjectMapper mapper) {
        FilterRegistrationBean<AuthenticationAttemptLimiter> registration =
                new FilterRegistrationBean<>(new AuthenticationAttemptLimiter(properties, clock, mapper));
        registration.setOrder(-101);
        registration.addUrlPatterns("/ui/*", "/v1/*", "/actuator/*");
        return registration;
    }
}
