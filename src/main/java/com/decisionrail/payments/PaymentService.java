package com.decisionrail.payments;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.decision.DecisionInput;
import com.decisionrail.decision.DecisionResult;
import com.decisionrail.telemetry.Correlation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentStore store;
    private final DecisionEngine engine;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Correlation correlation;

    public PaymentService(PaymentStore store, DecisionEngine engine, Clock clock, MeterRegistry metrics,
                          Correlation correlation) {
        this.store = store;
        this.engine = engine;
        this.clock = clock;
        this.metrics = metrics;
        this.correlation = correlation;
    }

    @Transactional(timeout = 15)
    public CommandResult authorize(String merchant, String key, AuthorizationCommand command) {
        DecisionInput input;
        try { input = new DecisionInput(command.amountMinor(), command.currency(), command.country()); }
        catch (IllegalArgumentException e) { throw new PaymentException("INVALID_PAYMENT_INPUT", 400, e.getMessage()); }
        if (command.accountId() == null) throw new PaymentException("INVALID_PAYMENT_INPUT", 400, "Account is required.");
        String currency = input.currency();
        String country = input.country();
        String fingerprint = "AUTHORIZE|" + command.accountId() + "|" + command.amountMinor() + "|" + currency + "|" + country;
        return idempotent(merchant, key, fingerprint, () -> {
            AccountView account = store.account(merchant, command.accountId(), true);
            if (!account.currency().equals(currency)) throw new PaymentException("CURRENCY_MISMATCH", 422, "Payment currency must match the account currency.");
            // Timed at the calling boundary. The evaluator itself stays free of Micrometer, Spring and
            // clocks, which is what lets replay and shadow reuse it unchanged.
            DecisionResult decision = decisionTimer().record(() -> engine.evaluate(input));
            PaymentStatus status = switch (decision.outcome()) {
                case APPROVE -> account.availableMinor() >= command.amountMinor() ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
                case REVIEW -> PaymentStatus.REVIEW;
                case DECLINE -> PaymentStatus.DECLINED;
            };
            String failureCode = decision.outcome().name().equals("APPROVE") && status == PaymentStatus.DECLINED ? "INSUFFICIENT_FUNDS" : null;
            Instant now = now();
            PaymentView payment = new PaymentView(UUID.randomUUID(), command.accountId(), command.amountMinor(), currency, country,
                    status, decision, failureCode, now, now);
            if (status == PaymentStatus.AUTHORIZED) store.reserve(account.id(), command.amountMinor());
            store.insert(merchant, payment);
            store.recordEvent(merchant, payment);
            // Counted after the writes are staged in this transaction and before it commits. The
            // counter says a command produced this outcome; whether the money moved is the ledger's
            // statement, not a metric's.
            recordOutcome("authorize", status, decision, failureCode);
            return new CommandResult(payment, 201, false);
        });
    }

    @Transactional(timeout = 15)
    public CommandResult capture(String merchant, String key, UUID paymentId) {
        return idempotent(merchant, key, "CAPTURE|" + paymentId, () -> transition(merchant, paymentId, PaymentStatus.CAPTURED));
    }

    @Transactional(timeout = 15)
    public CommandResult voidPayment(String merchant, String key, UUID paymentId) {
        return idempotent(merchant, key, "VOID|" + paymentId, () -> transition(merchant, paymentId, PaymentStatus.VOIDED));
    }

    @Transactional(readOnly = true)
    public PaymentView payment(String merchant, UUID id) { return store.payment(merchant, id, false); }

    @Transactional(readOnly = true)
    public AccountView account(String merchant, UUID id) { return store.account(merchant, id, false); }

    @Transactional(readOnly = true)
    public List<LedgerEntryView> ledger(String merchant, UUID id) { return store.ledger(merchant, id); }

    private CommandResult transition(String merchant, UUID id, PaymentStatus target) {
        // All lifecycle operations lock payment first, then its account. Authorization locks only account.
        PaymentView existing = store.payment(merchant, id, true);
        if (existing.status() != PaymentStatus.AUTHORIZED) {
            throw new PaymentException("INVALID_PAYMENT_STATE", 409, "Only an AUTHORIZED payment can be captured or voided.");
        }
        store.account(merchant, existing.accountId(), true);
        if (target == PaymentStatus.CAPTURED) {
            store.captureBalance(existing.accountId(), existing.amountMinor());
            store.recordJournal(merchant, existing);
        } else {
            store.release(existing.accountId(), existing.amountMinor());
        }
        Instant updatedAt = now();
        store.transition(id, target, updatedAt);
        PaymentView updated = new PaymentView(existing.id(), existing.accountId(), existing.amountMinor(), existing.currency(), existing.country(),
                target, existing.decision(), existing.failureCode(), existing.createdAt(), updatedAt);
        store.recordEvent(merchant, updated);
        recordOutcome(target == PaymentStatus.CAPTURED ? "capture" : "void", target, existing.decision(),
                existing.failureCode());
        return new CommandResult(updated, 200, false);
    }

    private CommandResult idempotent(String merchant, String key, String fingerprint, Supplier<CommandResult> action) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new PaymentException("INVALID_IDEMPOTENCY_KEY", 400, "Idempotency-Key must contain 8-128 letters, digits, dots, underscores, colons or hyphens.");
        }
        String hash = sha256(fingerprint);
        PaymentStore.IdempotencyRecord record = store.claimKey(merchant, key, hash);
        if (!record.requestHash().equals(hash)) {
            metrics.counter("decisionrail.idempotency.conflicts").increment();
            throw new PaymentException("IDEMPOTENCY_CONFLICT", 409, "This Idempotency-Key was already used for a different request.");
        }
        if (record.responseBody() != null) {
            metrics.counter("decisionrail.idempotency.replayed").increment();
            // This request is its own trace. It points at the operation it is replaying rather than
            // pretending to be it, and the original's provenance is left exactly as it was.
            if (record.originTraceId() != null) {
                correlation.tagCurrentSpan("decisionrail.replay_of_trace_id", record.originTraceId());
            }
            return new CommandResult(store.decodePayment(record.responseBody()), record.httpStatus(), true);
        }
        CommandResult result = action.get();
        store.completeKey(merchant, key, result);
        return result;
    }

    /**
     * How long evaluating the active policy took.
     *
     * <p>Deliberately separate from HTTP latency: rule evaluation is microseconds of pure computation,
     * and burying it inside a figure that also contains authentication, a database round trip and row
     * locking would say nothing about either.
     */
    private Timer decisionTimer() {
        return Timer.builder("decisionrail.decision.duration")
                .description("Policy evaluation only, excluding HTTP, authentication and database work")
                .publishPercentileHistogram()
                .register(metrics);
    }

    /**
     * What a command decided, as bounded facts, counted only once the transaction that decided it has
     * committed.
     *
     * <p>This used to increment inline, which made the counter a record of attempts wearing the name of
     * commitments. Anything failing after this point and before commit - storing the idempotent result,
     * the commit itself - rolled the money back and left the count standing. The catalogue promises one
     * increment per committed command, so the increment is deferred to an after-commit callback and
     * simply never happens on a rollback.
     *
     * <p>Every tag is a closed enumeration. No payment id, account id, merchant id or policy version
     * appears here: those grow without limit and would turn the metrics backend into its own outage.
     * A funding decline is tagged apart from a policy decline because they are different events with
     * different operator responses, and collapsing them is the mistake this project keeps correcting.
     *
     * <p>These remain process-local operational counters, not an accounting record. They reset when the
     * process restarts, and a crash between commit and callback loses an increment while the payment
     * stays committed. The ledger is the authority on what happened to money; this is for graphs.
     */
    private void recordOutcome(String operation, PaymentStatus status, DecisionResult decision, String failureCode) {
        Counter counter = Counter.builder("decisionrail.payments.commands")
                .description("Commands whose transaction committed, by outcome")
                .tag("operation", operation)
                .tag("status", status.name())
                .tag("risk_outcome", decision == null ? "none" : decision.outcome().name())
                .tag("funding", failureCode == null ? "none" : failureCode)
                .register(metrics);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait for. Every caller here is transactional, so this is a safety net
            // rather than a path in normal operation.
            counter.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    counter.increment();
                } catch (RuntimeException telemetryFailure) {
                    // The payment is already committed. A metrics failure must not propagate here and
                    // turn a successful command into an apparent failure for the caller.
                    log.warn("Could not record the committed-command metric; the payment is unaffected",
                            telemetryFailure);
                }
            }
        });
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
