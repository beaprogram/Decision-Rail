package com.decisionrail.publicdemo;

import com.decisionrail.payments.PaymentException;
import com.decisionrail.replay.ReplayService;
import com.decisionrail.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The visitor's "one replay job at a time" limit, under genuinely concurrent creation.
 *
 * <p>The limit used to be a count taken by an interceptor before the controller created the job.
 * Two requests arriving together could both count zero and both create, which the sequential test
 * could never show. This class drives the real service from several threads released by one latch,
 * against real PostgreSQL, and asserts on the rows that commit rather than on the responses alone.
 *
 * <p>The replay worker is off in the test profile, so a created job stays PENDING - in flight - until
 * this test says otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.public-demo.enabled=true",
        "app.public-demo.visitor-password=visitor-test-password-1234",
        "app.public-demo.max-running-replay-jobs=1",
        "app.public-demo.replay-jobs-per-hour=3",
        "app.public-demo.commands-per-minute=100",
        "app.ui.secure-cookies=true",
        "app.events.fault-injection-enabled=false"
})
class VisitorReplayConcurrencyTest {
    private static final String CANDIDATE_DEFINITION = """
            {"rules":[
              {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
               "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
               "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
            ]}""";

    @TestConfiguration
    static class AdvanceableTime {
        @Bean
        @Primary
        MutableClock replayTestClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
    }

    @Autowired ReplayService replay;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;

    private static final String VISITOR = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("visitor:visitor-test-password-1234".getBytes());

    private String candidate;

    @BeforeEach
    void freshCandidateAndNoVisitorJobs() throws Exception {
        // Start from no visitor jobs, so the in-flight and hourly counts are this test's own. The
        // suite shares one database, so this is what isolates the class from its neighbours.
        jdbc.update("DELETE FROM replay_results WHERE job_id IN (SELECT id FROM replay_jobs WHERE merchant_id = 'visitor')");
        jdbc.update("DELETE FROM replay_job_items WHERE job_id IN (SELECT id FROM replay_jobs WHERE merchant_id = 'visitor')");
        jdbc.update("DELETE FROM replay_job_requests WHERE merchant_id = 'visitor'");
        jdbc.update("DELETE FROM replay_jobs WHERE merchant_id = 'visitor'");
        clock.advance(Duration.ofHours(2));
        candidate = "concurrency-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/v1/policies")
                .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("admin:admin-test-password-123".getBytes()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionId\":\"" + candidate + "\",\"definition\":" + CANDIDATE_DEFINITION + "}"))
                .andReturn();
    }

    @Test
    void concurrentDistinctRequestsCannotExceedOneJobInFlight() throws Exception {
        // Through HTTP, because that is where the limit used to be checked - by an interceptor that
        // counted rows and then let the controller create one. Eight requests released together.
        int competitors = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(competitors);
        List<Future<Outcome>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < competitors; i++) {
                String key = "concurrent-" + UUID.randomUUID();
                outcomes.add(pool.submit((Callable<Outcome>) () -> {
                    go.await(10, TimeUnit.SECONDS);
                    var result = mvc.perform(post("/v1/replay-jobs")
                            .header("Authorization", VISITOR)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateVersion\":\"" + candidate + "\",\"limit\":10}")).andReturn();
                    return new Outcome(key, result.getResponse().getStatus(), result.getResponse().getContentAsString());
                }));
            }
            go.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : outcomes) results.add(future.get(30, TimeUnit.SECONDS));

            long created = results.stream().filter(o -> o.status() == 201).count();
            long refused = results.stream().filter(o -> o.status() == 429).count();
            assertThat(created).as("exactly one creator wins: %s", results).isEqualTo(1);
            assertThat(refused).isEqualTo(competitors - 1);
            for (Outcome outcome : results) {
                if (outcome.status() == 429) {
                    assertThat(outcome.body()).contains("DEMO_CAPACITY_EXHAUSTED").contains("already has a replay job running");
                    // A refusal leaves nothing: no job, no membership, no request record for its key.
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM replay_job_requests WHERE merchant_id = 'visitor' AND idempotency_key = ?",
                            Long.class, outcome.key())).isZero();
                }
            }
            // The rows agree with the responses: one job, in flight, and nothing orphaned.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM replay_jobs WHERE merchant_id = 'visitor'", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM replay_jobs WHERE merchant_id = 'visitor' AND status IN ('PENDING','RUNNING')", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM replay_job_requests WHERE merchant_id = 'visitor'", Long.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSameKeyRetryReplaysWithoutBeingAdmittedAgain() {
        String key = "retry-" + UUID.randomUUID();
        ReplayService.CreatedJob first = replay.create("visitor", key, new ReplayService.CreateCommand(candidate, 10, null));
        assertThat(first.replayed()).isFalse();
        // One in flight now. A retry of the same key is the same job, not a second admission.
        ReplayService.CreatedJob again = replay.create("visitor", key, new ReplayService.CreateCommand(candidate, 10, null));
        assertThat(again.replayed()).isTrue();
        assertThat(again.job().id()).isEqualTo(first.job().id());
        // And a different key is refused, which is what proves the retry was not admitted anew.
        assertThatThrownBy(() -> replay.create("visitor", "other-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null)))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).status()).isEqualTo(429));
    }

    @Test
    void capacityReturnsWhenAJobReachesATerminalStateAndTheHourlyAllowanceIsSeparate() {
        UUID first = replay.create("visitor", "first-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null)).job().id();
        assertThatThrownBy(() -> replay.create("visitor", "second-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null)))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("already has a replay job running");

        // Terminal: COMPLETED or FAILED both release the in-flight slot.
        jdbc.update("UPDATE replay_jobs SET status = 'COMPLETED' WHERE id = ?", first);
        UUID second = replay.create("visitor", "second-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null)).job().id();
        jdbc.update("UPDATE replay_jobs SET status = 'FAILED' WHERE id = ?", second);
        replay.create("visitor", "third-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null));
        jdbc.update("UPDATE replay_jobs SET status = 'COMPLETED' WHERE merchant_id = 'visitor'");

        // Nothing in flight, but three created this hour: the other control answers now, by name.
        assertThatThrownBy(() -> replay.create("visitor", "fourth-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null)))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("hourly allowance");
        // An hour later the allowance is back. The hour is the database's, since it stamps created_at,
        // so the rows are aged rather than the application clock advanced.
        jdbc.update("UPDATE replay_jobs SET created_at = created_at - interval '61 minutes' WHERE merchant_id = 'visitor'");
        replay.create("visitor", "fifth-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null));
        for (int i = 0; i < 5; i++) {
            replay.create("demo-merchant", "private-" + UUID.randomUUID(), new ReplayService.CreateCommand(candidate, 10, null));
        }
    }

    private record Outcome(String key, int status, String body) {}
}
