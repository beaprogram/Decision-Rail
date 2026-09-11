package com.decisionrail.health;

import com.decisionrail.resilience.CircuitBreaker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * HTTP status codes returned by the health endpoints, using the application's real health
 * configuration.
 *
 * <p>This exists because a custom {@code http-mapping} entry silently replaces Spring Boot's
 * default mappings rather than adding to them. Declaring only DEGRADED therefore made DOWN and
 * OUT_OF_SERVICE fall back to HTTP 200, so an instance whose database was unreachable still
 * answered readiness with a success code and would have stayed in rotation.
 *
 * <p>The database failure is produced by a wrapper around the real {@link DataSource} that refuses
 * connections on demand. Nothing stops a container, so this never touches a development database,
 * and the application still starts and migrates normally before the switch is armed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthStatusMappingTest {

    @DynamicPropertySource
    static void quietenAsyncWorkers(DynamicPropertyRegistry registry) {
        // This test is about health codes, not delivery. Listeners would only add reconnect noise.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @TestConfiguration
    static class FailableDataSourceConfiguration {
        /**
         * Wraps the real DataSource rather than replacing the bean, so migrations and the pool are
         * configured exactly as in production and the indicator under test sees the wrapper.
         */
        @Bean
        static BeanPostProcessor failableDataSourceWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource delegate ? new FailableDataSource(delegate) : bean;
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired HttpCodeStatusMapper httpCodeStatusMapper;
    @Autowired CircuitBreaker brokerBreaker;

    @BeforeEach
    void healthyStart() {
        FailableDataSource.refuseConnections = false;
        brokerBreaker.reset();
    }

    @AfterEach
    void restore() {
        FailableDataSource.refuseConnections = false;
        brokerBreaker.reset();
    }

    @Test
    void anUnreachableDatabaseMakesReadinessReportDownWithServiceUnavailable() throws Exception {
        Response healthy = call("/actuator/health/readiness");
        assertThat(healthy.status()).isEqualTo(200);
        assertThat(healthy.body().path("status").asText()).isEqualTo("UP");

        FailableDataSource.refuseConnections = true;
        Response unhealthy = call("/actuator/health/readiness");

        // The body already said DOWN before this fix; the status code did not.
        assertThat(unhealthy.body().path("status").asText()).isEqualTo("DOWN");
        assertThat(unhealthy.status())
                .as("a readiness probe reporting DOWN must not answer with a success code")
                .isEqualTo(503);

        FailableDataSource.refuseConnections = false;
        Response recovered = call("/actuator/health/readiness");
        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.body().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void theAggregateHealthEndpointAlsoReportsServiceUnavailableWhenDown() throws Exception {
        FailableDataSource.refuseConnections = true;
        Response aggregate = call("/actuator/health");
        assertThat(aggregate.body().path("status").asText()).isEqualTo("DOWN");
        assertThat(aggregate.status()).isEqualTo(503);
    }

    @Test
    void livenessStaysUpAndSuccessfulWhileTheDatabaseIsUnreachable() throws Exception {
        FailableDataSource.refuseConnections = true;
        Response liveness = call("/actuator/health/liveness");

        // Liveness answers "is this process alive". A dependency outage is not a reason to be killed.
        assertThat(liveness.status()).isEqualTo(200);
        assertThat(liveness.body().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void aBrokerOnlyFailureLeavesReadinessUpWhileAsynchronousCapabilityReportsDegraded() throws Exception {
        // Open the breaker without touching the database: this is a broker-only impairment.
        openBrokerBreaker();

        Response readiness = call("/actuator/health/readiness");
        assertThat(readiness.status())
                .as("a broker outage must not remove a correct payment API from rotation")
                .isEqualTo(200);
        assertThat(readiness.body().path("status").asText()).isEqualTo("UP");

        Response async = call("/actuator/health/async");
        assertThat(async.body().path("status").asText()).isEqualTo("DEGRADED");
        // DEGRADED is an operational signal, not an unhealthy instance, so it keeps a success code.
        assertThat(async.status()).isEqualTo(200);
        assertThat(async.body().path("components").path("asyncDelivery").path("details")
                .path("paymentApiAffected").asBoolean()).isFalse();

        Response liveness = call("/actuator/health/liveness");
        assertThat(liveness.status()).isEqualTo(200);
        assertThat(liveness.body().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void theConfiguredMapperKeepsEveryUnhealthyStatusOnServiceUnavailable() {
        // The application's own configured mapper, not a standalone one: a custom http-mapping
        // replaces the defaults, so every unhealthy status has to be declared explicitly.
        assertThat(httpCodeStatusMapper.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.UP)).isEqualTo(200);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.UNKNOWN)).isEqualTo(200);
        assertThat(httpCodeStatusMapper.getStatusCode(new Status("DEGRADED"))).isEqualTo(200);
    }

    private void openBrokerBreaker() {
        for (int failure = 0; failure < 50; failure++) {
            if (brokerBreaker.state() != CircuitBreaker.State.CLOSED) return;
            brokerBreaker.tryAcquire();
            brokerBreaker.recordFailure();
        }
        assertThat(brokerBreaker.state()).isNotEqualTo(CircuitBreaker.State.CLOSED);
    }

    private record Response(int status, JsonNode body) {}

    private Response call(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        String body = result.getResponse().getContentAsString();
        return new Response(result.getResponse().getStatus(),
                body.isBlank() ? json.nullNode() : json.readTree(body));
    }

    /** Delegating DataSource that refuses connections while armed. */
    static class FailableDataSource implements DataSource {
        static volatile boolean refuseConnections;
        private final DataSource delegate;

        FailableDataSource(DataSource delegate) { this.delegate = delegate; }

        private void guard() throws SQLException {
            if (refuseConnections) throw new SQLException("Injected database outage");
        }

        @Override public Connection getConnection() throws SQLException { guard(); return delegate.getConnection(); }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            guard();
            return delegate.getConnection(username, password);
        }

        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }

        @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }

        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }

        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }

        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
        }

        @Override public boolean isWrapperFor(Class<?> type) throws SQLException {
            return type.isInstance(this) || delegate.isWrapperFor(type);
        }
    }
}
