package com.decisionrail.telemetry;

import com.decisionrail.payments.AuthorizationCommand;
import com.decisionrail.payments.CommandResult;
import com.decisionrail.payments.PaymentView;
import com.decisionrail.payments.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the committed-command counter actually counts.
 *
 * <p>The metric catalogue promises one increment per committed command. It used to increment inline,
 * part-way through the transaction, which made it a count of attempts under the name of commitments:
 * anything failing after that point and before commit rolled the money back and left the count
 * standing.
 *
 * <p>The rollback here is driven by the surrounding transaction rather than by a failpoint, because the
 * existing outbox failpoint fires <em>before</em> execution reaches the instrumentation, and would
 * therefore pass whether the counter were deferred or not.
 */
@SpringBootTest
@ActiveProfiles("test")
class CommittedCommandMetricTest {
    private static final String MERCHANT = "demo-merchant";

    @DynamicPropertySource
    static void noListeners(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    @Autowired TransactionTemplate transactions;

    private UUID account;

    @BeforeEach
    void newAccount() {
        account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                account, MERCHANT, 100_000, 100_000);
    }

    @Test
    void aRollbackAfterTheInstrumentationLeavesNoIncrementAndNoMoneyMoved() {
        double before = committedCommands();
        String key = "rollback-" + UUID.randomUUID();

        // The service joins this transaction, so execution reaches the counter and then the whole thing
        // is discarded: exactly the window a completeKey or commit failure opens.
        transactions.executeWithoutResult(status -> {
            CommandResult<PaymentView> result = payments.authorize(MERCHANT, key,
                    new AuthorizationCommand(account, 2_500, "CAD", "CA"));
            assertThat(result.body().status().name()).isEqualTo("AUTHORIZED");
            status.setRollbackOnly();
        });

        assertThat(committedCommands())
                .as("a command whose transaction rolled back is not a committed command")
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE account_id = ?", Long.class, account))
                .as("and nothing durable survived it either")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT held_minor FROM accounts WHERE id = ?", Long.class, account))
                .isZero();
    }

    @Test
    void aCommittedCommandIncrementsExactlyOnceAndAReplayDoesNotIncrementAgain() {
        double before = committedCommands();
        String key = "committed-" + UUID.randomUUID();
        AuthorizationCommand command = new AuthorizationCommand(account, 3_100, "CAD", "CA");

        CommandResult<PaymentView> first = payments.authorize(MERCHANT, key, command);
        assertThat(first.replayed()).isFalse();
        assertThat(committedCommands()).isEqualTo(before + 1);

        // The same key and the same request. No new command was performed, so nothing new is counted.
        CommandResult<PaymentView> replay = payments.authorize(MERCHANT, key, command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().id()).isEqualTo(first.body().id());
        assertThat(committedCommands())
                .as("an idempotent replay performs no command, so it commits none")
                .isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE account_id = ?", Long.class, account))
                .isEqualTo(1L);
    }

    /** Every series of the committed-command counter, summed: the tags vary by outcome. */
    private double committedCommands() {
        return metrics.find("decisionrail.payments.commands").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }
}
