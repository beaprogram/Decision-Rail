package com.decisionrail.payments;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.publicdemo.PublicDemoProperties;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Mirrors the payment amount bound and payment_returns.amount_minor. */
    private static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
    /** Mirrors payment_returns.reason varchar(140). */
    private static final int MAX_RETURN_REASON = 140;

    private final PaymentStore store;
    private final ReturnStore returns;
    private final DecisionEngine engine;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Correlation correlation;
    private final PublicDemoProperties publicDemo;

    public PaymentService(PaymentStore store, ReturnStore returns, DecisionEngine engine, Clock clock,
                          MeterRegistry metrics, Correlation correlation, PublicDemoProperties publicDemo) {
        this.store = store;
        this.returns = returns;
        this.engine = engine;
        this.clock = clock;
        this.metrics = metrics;
        this.correlation = correlation;
        this.publicDemo = publicDemo;
    }

    @Transactional(timeout = 15)
    public CommandResult<PaymentView> authorize(String merchant, String key, AuthorizationCommand command) {
        DecisionInput input;
        try { input = new DecisionInput(command.amountMinor(), command.currency(), command.country()); }
        catch (IllegalArgumentException e) { throw new PaymentException("INVALID_PAYMENT_INPUT", 400, e.getMessage()); }
        if (command.accountId() == null) throw new PaymentException("INVALID_PAYMENT_INPUT", 400, "Account is required.");
        String currency = input.currency();
        String country = input.country();
        String fingerprint = "AUTHORIZE|" + command.accountId() + "|" + command.amountMinor() + "|" + currency + "|" + country;
        return idempotent(merchant, key, fingerprint, StoredResponseKind.PAYMENT, null,
                stored -> stored instanceof PaymentView payment
                        && payment.accountId().equals(command.accountId())
                        && payment.amountMinor() == command.amountMinor()
                        && payment.currency().equals(currency)
                        && payment.country().equals(country),
                () -> {
            AccountView account = store.account(merchant, command.accountId(), true);
            if (!account.currency().equals(currency)) throw new PaymentException("CURRENCY_MISMATCH", 422, "Payment currency must match the account currency.");
            // The public visitor's accounts have a bounded history, because reconciliation walks all
            // of it and the instance is shared. Checked inside the idempotent action, after the key
            // is claimed and before anything is written: a retry of an authorization that already
            // committed still replays its receipt, and a refusal rolls the claim back so the key is
            // not consumed by a request that did nothing.
            if (publicDemo.isVisitor(merchant)
                    && store.paymentCount(account.id()) >= publicDemo.maxPaymentsPerAccount()) {
                throw new PaymentException("DEMO_ACCOUNT_FULL", 429,
                        "This demo account has reached its limit of " + publicDemo.maxPaymentsPerAccount()
                                + " payments. Use another demo account; nothing was changed by this request.");
            }
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
            return new CommandResult<>(payment, 201, false);
        });
    }

    @Transactional(timeout = 15)
    public CommandResult<PaymentView> capture(String merchant, String key, UUID paymentId) {
        // No superseded fingerprint: this encoding is unchanged. Capture, void and authorize
        // fingerprints contain no optional free text, so they were never ambiguous and rewriting them
        // would break every key already stored against them for no benefit. The receipt is still
        // checked, because a stored response naming a different payment is not this command's answer
        // however the hash came to match.
        return idempotent(merchant, key, "CAPTURE|" + paymentId, StoredResponseKind.PAYMENT, null,
                describesTheSamePayment(paymentId),
                () -> transition(merchant, paymentId, PaymentStatus.CAPTURED));
    }

    @Transactional(timeout = 15)
    public CommandResult<PaymentView> voidPayment(String merchant, String key, UUID paymentId) {
        return idempotent(merchant, key, "VOID|" + paymentId, StoredResponseKind.PAYMENT, null,
                describesTheSamePayment(paymentId),
                () -> transition(merchant, paymentId, PaymentStatus.VOIDED));
    }

    /**
     * Returns captured money, in part or in full, or reverses a capture outright.
     *
     * <p>Every effect of a successful return commits together: the return operation, the balanced
     * compensating journal, the credit back to the funding account, the payment's new returned total,
     * the audit record, the durable event intent and the stored idempotent response. There is no
     * partial success. A failure at any point - a budget exceeded, a database error, a rejected
     * journal - rolls all of it back, and the caller is told the command did not happen.
     *
     * <p>Locks are taken in the same order every other lifecycle command uses: the payment row first,
     * then its account. Two refunds racing on one payment therefore serialise on the payment, and a
     * refund racing with an authorization on a shared account serialises on the account. Taking the
     * account first here would be the classic way to deadlock against capture.
     *
     * <p>Nothing about the original authorization, the capture amount, or the capture journal changes.
     * The capture happened; a return is a new, separate, compensating operation that says so.
     */
    @Transactional(timeout = 15)
    public CommandResult<ReturnReceiptView> returnFunds(String merchant, String key, ReturnCommand command) {
        validateReturnCommand(command);
        String reason = normaliseReason(command.reason());
        // The amount is part of the identity of a refund, so two refunds of different amounts can never
        // collapse onto one key. A reversal names no amount because it always returns the whole
        // capture; including one would invent a distinction the operation does not have.
        //
        // The reason is length-prefixed rather than concatenated. Concatenation renders a null
        // reference as the four characters "null", so "no reason given" and "the reason is the word
        // null" produced one identity and each could replay the other's receipt.
        String fingerprint = command.type() == ReturnType.REFUND
                ? "REFUND|" + command.paymentId() + "|" + command.amountMinor() + "|" + canonical(reason)
                : "REVERSAL|" + command.paymentId() + "|" + canonical(reason);
        return idempotent(merchant, key, fingerprint, StoredResponseKind.RETURN,
                supersededReturnFingerprint(command, reason), describesTheSameReturn(command, reason),
                () -> performReturn(merchant, command, reason));
    }

    private CommandResult<ReturnReceiptView> performReturn(String merchant, ReturnCommand command, String reason) {
        PaymentView payment = store.payment(merchant, command.paymentId(), true);
        if (payment.status() != PaymentStatus.CAPTURED) {
            throw new PaymentException("INVALID_PAYMENT_STATE", 409,
                    "Only a captured payment can be refunded or reversed.");
        }
        Long captured = store.capturedAmount(payment.id());
        if (captured == null) {
            // The database requires a CAPTURED payment to state its captured amount, so reaching here
            // means the schema guarantee was lost rather than that the caller did anything wrong.
            throw new IllegalStateException("Captured payment " + payment.id() + " has no captured amount");
        }
        store.account(merchant, payment.accountId(), true);

        // Summed from the return operations themselves while both rows are held, so the budget is
        // decided by what was actually returned rather than by a denormalised total that a defect
        // could have left stale. A deferred trigger refuses the transaction if the two disagree.
        ReturnStore.Totals totals = returns.totals(payment.id());
        long remaining = captured - totals.returnedMinor();

        long amount;
        if (command.type() == ReturnType.REVERSAL) {
            if (totals.count() > 0) {
                throw new PaymentException("PAYMENT_NOT_REVERSIBLE", 409,
                        "This capture has already been returned in part, so it cannot be reversed. "
                                + "Refund the remaining amount instead.");
            }
            amount = captured;
        } else {
            amount = command.amountMinor();
        }
        if (amount > remaining) {
            throw new PaymentException("RETURN_EXCEEDS_REFUNDABLE", 422,
                    "This payment has " + remaining + " minor units left to return.");
        }

        // Exact integer arithmetic, and it throws rather than wrapping. Both operands are already
        // bounded well below the overflow point; this is here so that stays true if the bound moves.
        long returnedAfter = Math.addExact(totals.returnedMinor(), amount);
        Instant now = now();
        UUID returnId = UUID.randomUUID();
        int sequenceNumber = totals.highestSequence() + 1;

        UUID journalId = returns.record(merchant, payment, returnId, command.type(), amount, reason,
                sequenceNumber, now);
        returns.creditBack(payment.accountId(), amount);
        returns.recordReturnedTotal(payment.id(), returnedAfter, now);

        ReturnReceiptView receipt = new ReturnReceiptView(returnId, payment.id(), payment.accountId(),
                command.type(), amount, payment.currency(), reason, sequenceNumber, journalId,
                captured, returnedAfter, captured - returnedAfter, now);
        PaymentView updated = new PaymentView(payment.id(), payment.accountId(), payment.amountMinor(),
                payment.currency(), payment.country(), payment.status(), payment.decision(),
                payment.failureCode(), payment.createdAt(), now);
        store.recordReturnEvent(merchant, updated, receipt);
        recordOutcome(command.type() == ReturnType.REFUND ? "refund" : "reversal", payment.status(),
                payment.decision(), payment.failureCode());
        recordReturn(command.type(), returnedAfter == captured);
        return new CommandResult<>(receipt, 201, false);
    }

    /**
     * What a return command has to say before anything is read or locked.
     *
     * <p>A refund states its amount exactly, including when it is returning everything that is left.
     * "Refund the remainder" is deliberately not a request the server accepts: the remainder changes
     * as other refunds commit, so the same key could mean two different amounts at two different
     * moments, and an idempotency key whose meaning drifts is worse than no key at all. The dashboard
     * reads the remaining amount and sends that number.
     */
    private static void validateReturnCommand(ReturnCommand command) {
        if (command.paymentId() == null) {
            throw new PaymentException("INVALID_RETURN_INPUT", 400, "Payment is required.");
        }
        if (command.type() == null) {
            throw new PaymentException("INVALID_RETURN_INPUT", 400, "Return type is required.");
        }
        if (command.type() == ReturnType.REFUND) {
            if (command.amountMinor() == null) {
                throw new PaymentException("INVALID_RETURN_INPUT", 400,
                        "A refund must state the exact amount to return, in minor units.");
            }
            if (command.amountMinor() <= 0 || command.amountMinor() > MAX_AMOUNT_MINOR) {
                throw new PaymentException("INVALID_RETURN_INPUT", 400,
                        "A refund amount must be between 1 and " + MAX_AMOUNT_MINOR + " minor units.");
            }
        } else if (command.amountMinor() != null) {
            throw new PaymentException("INVALID_RETURN_INPUT", 400,
                    "A reversal returns the whole captured amount and must not state one.");
        }
        if (command.reason() != null && command.reason().strip().length() > MAX_RETURN_REASON) {
            throw new PaymentException("INVALID_RETURN_INPUT", 400,
                    "A reason may contain at most " + MAX_RETURN_REASON + " characters.");
        }
    }

    /**
     * Blank and absent mean the same thing, and both are stored as absent so they fingerprint alike.
     *
     * <p>Surrounding whitespace is stripped, so {@code " refund "} and {@code "refund"} are one reason.
     * A reason that is entirely whitespace, or an empty string, becomes absent. Everything else is kept
     * exactly as typed, including a reason that happens to read {@code "null"}.
     */
    private static String normaliseReason(String reason) {
        if (reason == null) return null;
        String stripped = reason.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * One unambiguous encoding of an optional free-text field.
     *
     * <p>Absent is a distinct token that no present value can produce, and a present value carries its
     * own length, so no value can be mistaken for a different one by borrowing the separator. Hashing
     * this is what makes the fingerprint an identity; hashing a concatenation would still be hashing an
     * ambiguous string, however good the hash.
     */
    private static String canonical(String value) {
        return value == null ? "-" : value.length() + ":" + value;
    }

    /**
     * The fingerprint a return key written before the canonical encoding would carry.
     *
     * <p>Still computed so a legitimate retry of a return that already committed is recognised rather
     * than refused as a conflict. It is only ever a second hash to accept; whether the key really is
     * this command is settled by the stored receipt, which is checked either way.
     */
    private String supersededReturnFingerprint(ReturnCommand command, String reason) {
        return command.type() == ReturnType.REFUND
                ? "REFUND|" + command.paymentId() + "|" + command.amountMinor() + "|" + reason
                : "REVERSAL|" + command.paymentId() + "|" + reason;
    }

    /**
     * Whether a stored receipt is the answer to this return command.
     *
     * <p>Every field a caller can vary is compared, because the receipt is what makes a replay
     * truthful: the payment it belongs to, which kind of return it was, the reason as normalised, and
     * for a refund the exact amount. A reversal names no amount - it always returns the whole capture -
     * so there is nothing of the caller's to compare there.
     */
    private static Predicate<Object> describesTheSameReturn(ReturnCommand command, String reason) {
        return stored -> stored instanceof ReturnReceiptView receipt
                && receipt.paymentId().equals(command.paymentId())
                && receipt.returnType() == command.type()
                && java.util.Objects.equals(receipt.reason(), reason)
                && (command.type() != ReturnType.REFUND
                        || receipt.amountMinor() == command.amountMinor());
    }

    @Transactional(readOnly = true)
    public PaymentView payment(String merchant, UUID id) { return store.payment(merchant, id, false); }

    /**
     * What may still be returned on one payment, and what already has been.
     *
     * <p>The server decides availability and says why, rather than publishing the raw numbers and
     * leaving each caller to re-derive the rule. A dashboard that computed "reversible" for itself
     * would be a second implementation of a financial rule, and the two would drift.
     */
    @Transactional(readOnly = true)
    public PaymentReturnsView returns(String merchant, UUID paymentId, String cursor, Integer limit) {
        PaymentView payment = store.payment(merchant, paymentId, false);
        Long captured = store.capturedAmount(paymentId);
        int page = boundedPage(limit);
        ReturnStore.Page history = returns.forPayment(merchant, paymentId,
                ReturnCursor.decode(cursor, paymentId), page);
        ReturnStore.Totals totals = returns.totals(paymentId);
        long returned = payment.status() == PaymentStatus.CAPTURED ? totals.returnedMinor() : 0L;
        long remaining = captured == null ? 0L : captured - returned;
        boolean refundable = captured != null && remaining > 0;
        // From the payment's own return count, not from the page. A history page can be empty because
        // the caller paged past the end, and deciding eligibility from that would offer a reversal on a
        // capture that has already been returned.
        boolean reversible = captured != null && totals.count() == 0;
        String unavailableReason = null;
        if (captured == null) {
            unavailableReason = "NOT_CAPTURED";
        } else if (remaining == 0) {
            unavailableReason = "FULLY_RETURNED";
        } else if (totals.count() > 0) {
            // Refunds remain possible; only the reversal is closed off. Named separately so the
            // dashboard can explain which control is gone and why.
            unavailableReason = "PARTIALLY_RETURNED";
        }
        return new PaymentReturnsView(payment.id(), payment.accountId(), payment.status(), payment.currency(),
                captured, returned, remaining, refundable, reversible, unavailableReason,
                totals.count(), history.returns(), history.nextCursor(), page);
    }

    /** Page size, defaulted and capped. An out-of-range request is refused rather than quietly clamped. */
    private static int boundedPage(Integer limit) {
        if (limit == null) return ReturnStore.DEFAULT_PAGE;
        if (limit < 1 || limit > ReturnStore.MAX_PAGE) {
            throw new PaymentException("INVALID_RETURN_INPUT", 400,
                    "limit must be between 1 and " + ReturnStore.MAX_PAGE + ".");
        }
        return limit;
    }

    @Transactional(readOnly = true)
    public AccountView account(String merchant, UUID id) { return store.account(merchant, id, false); }

    @Transactional(readOnly = true)
    public List<LedgerEntryView> ledger(String merchant, UUID id) { return store.ledger(merchant, id); }

    /** A capture or void replay must describe the payment the path named. */
    private static Predicate<Object> describesTheSamePayment(UUID paymentId) {
        return stored -> stored instanceof PaymentView payment && payment.id().equals(paymentId);
    }

    private CommandResult<PaymentView> transition(String merchant, UUID id, PaymentStatus target) {
        // All lifecycle operations lock payment first, then its account. Authorization locks only account.
        PaymentView existing = store.payment(merchant, id, true);
        if (existing.status() != PaymentStatus.AUTHORIZED) {
            throw new PaymentException("INVALID_PAYMENT_STATE", 409, "Only an AUTHORIZED payment can be captured or voided.");
        }
        store.account(merchant, existing.accountId(), true);
        Instant updatedAt = now();
        if (target == PaymentStatus.CAPTURED) {
            store.captureBalance(existing.accountId(), existing.amountMinor());
            // The status and the captured amount move together, and the journal is written against
            // them. The database checks the agreement at commit: a capture journal whose amount does
            // not equal the recorded capture is refused.
            store.capture(id, existing.amountMinor(), updatedAt);
            store.recordJournal(merchant, existing);
        } else {
            store.release(existing.accountId(), existing.amountMinor());
            store.transition(id, target, updatedAt);
        }
        PaymentView updated = new PaymentView(existing.id(), existing.accountId(), existing.amountMinor(), existing.currency(), existing.country(),
                target, existing.decision(), existing.failureCode(), existing.createdAt(), updatedAt);
        store.recordEvent(merchant, updated);
        recordOutcome(target == PaymentStatus.CAPTURED ? "capture" : "void", target, existing.decision(),
                existing.failureCode());
        return new CommandResult<>(updated, 200, false);
    }

    /**
     * Runs a command exactly once per merchant-scoped key, whatever shape its response takes.
     *
     * <p>{@code kind} is stored with the response and required to match on replay. That is not
     * decoration: the fingerprint already refuses a key reused for a different request, and this is
     * the second line - if the two ever disagreed, decoding a refund receipt as a payment would
     * either throw or, against a lenient reader, hand back a plausible-looking object that was never
     * what the caller was told.
     *
     * <p>A replay returns exactly the bytes that were stored. Nothing here consults the payment's
     * current state: a receipt written before two further refunds still reports the totals as they
     * were when it committed, because that is what the caller was told and what they may be
     * reconciling against.
     */
    private <T> CommandResult<T> idempotent(String merchant, String key, String fingerprint,
                                            StoredResponseKind kind, String supersededFingerprint,
                                            Predicate<Object> describesThisCommand,
                                            Supplier<CommandResult<T>> action) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new PaymentException("INVALID_IDEMPOTENCY_KEY", 400, "Idempotency-Key must contain 8-128 letters, digits, dots, underscores, colons or hyphens.");
        }
        String hash = sha256(fingerprint);
        PaymentStore.IdempotencyRecord record = store.claimKey(merchant, key, hash);
        boolean hashRecognised = record.requestHash().equals(hash)
                || (supersededFingerprint != null && sha256(supersededFingerprint).equals(record.requestHash()));
        if (!hashRecognised) {
            throw conflict();
        }
        if (record.responseBody() != null) {
            // The stored response is the authority on what this key answered for, and it is consulted
            // on every replay - not only when the hash came from an older encoding.
            //
            // A hash is a summary of a request, and two encodings of the same field share one space:
            // a record written when the reason was concatenated raw, whose reason happened to read
            // like the text the current encoding produces, hashes exactly where a different request
            // hashes today. A direct hash match was therefore not proof of sameness, and accepting it
            // as proof replayed one command's receipt for another's request. The receipt records what
            // the operation actually was, so comparing against it settles the question whichever
            // generation wrote the row.
            StoredResponseKind stored = record.responseKind() == null ? StoredResponseKind.PAYMENT : record.responseKind();
            if (stored != kind) {
                throw conflict();
            }
            Object decoded = store.decodeResponse(record, kind);
            if (!describesThisCommand.test(decoded)) {
                throw conflict();
            }
            metrics.counter("decisionrail.idempotency.replayed").increment();
            // This request is its own trace. It points at the operation it is replaying rather than
            // pretending to be it, and the original's provenance is left exactly as it was.
            if (record.originTraceId() != null) {
                correlation.tagCurrentSpan("decisionrail.replay_of_trace_id", record.originTraceId());
            }
            @SuppressWarnings("unchecked")
            T body = (T) decoded;
            return new CommandResult<>(body, record.httpStatus(), true);
        }
        CommandResult<T> result = action.get();
        store.completeKey(merchant, key, kind, result);
        return result;
    }

    private PaymentException conflict() {
        metrics.counter("decisionrail.idempotency.conflicts").increment();
        return new PaymentException("IDEMPOTENCY_CONFLICT", 409,
                "This Idempotency-Key was already used for a different request.");
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
        incrementOnCommit(counter);
    }

    /**
     * How much of a capture a return took back, which the command counter cannot say.
     *
     * <p>Both tags are closed enumerations, four series in total. "Did partial refunds become common"
     * and "how often is a capture undone outright" are ordinary operational questions, and neither can
     * be answered from a counter that only knows an operation happened. Amounts deliberately do not
     * appear: money belongs in the ledger and the operator APIs, not in a metric label or a gauge that
     * resets when the process restarts.
     */
    private void recordReturn(ReturnType type, boolean exhaustsTheCapture) {
        incrementOnCommit(Counter.builder("decisionrail.payments.returns")
                .description("Returns whose transaction committed, by type and extent")
                .tag("return_type", type.name())
                .tag("extent", exhaustsTheCapture ? "FULL" : "PARTIAL")
                .register(metrics));
    }

    /** Counts a committed outcome, never an attempt, and never at the cost of the outcome itself. */
    private void incrementOnCommit(Counter counter) {
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
