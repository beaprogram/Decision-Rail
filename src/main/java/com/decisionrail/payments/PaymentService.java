package com.decisionrail.payments;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.decision.DecisionInput;
import com.decisionrail.decision.DecisionResult;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final PaymentStore store;
    private final DecisionEngine engine;
    private final Clock clock;
    private final MeterRegistry metrics;

    public PaymentService(PaymentStore store, DecisionEngine engine, Clock clock, MeterRegistry metrics) {
        this.store = store;
        this.engine = engine;
        this.clock = clock;
        this.metrics = metrics;
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
            DecisionResult decision = metrics.timer("decisionrail.decision.duration").record(() -> engine.evaluate(input));
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
        return new CommandResult(updated, 200, false);
    }

    private CommandResult idempotent(String merchant, String key, String fingerprint, Supplier<CommandResult> action) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new PaymentException("INVALID_IDEMPOTENCY_KEY", 400, "Idempotency-Key must contain 8-128 letters, digits, dots, underscores, colons or hyphens.");
        }
        String hash = sha256(fingerprint);
        PaymentStore.IdempotencyRecord record = store.claimKey(merchant, key, hash);
        if (!record.requestHash().equals(hash)) throw new PaymentException("IDEMPOTENCY_CONFLICT", 409, "This Idempotency-Key was already used for a different request.");
        if (record.responseBody() != null) {
            metrics.counter("decisionrail.idempotency.replayed").increment();
            return new CommandResult(store.decodePayment(record.responseBody()), record.httpStatus(), true);
        }
        CommandResult result = action.get();
        store.completeKey(merchant, key, result);
        return result;
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
