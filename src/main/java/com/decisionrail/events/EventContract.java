package com.decisionrail.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Parses and validates a consumed record against the supported event contract.
 *
 * <p>{@code schemaVersion} is the compatibility gate, so unknown additional fields are
 * tolerated on purpose: an additive producer change must not break a running consumer.
 * Anything that would make the event ambiguous - missing identity, an unsupported schema or
 * type, a decision outside its declared range - is refused instead of guessed at.
 */
@Component
public class EventContract {
    /** Lifecycle events this consumer understands, one per payment status. */
    public static final Set<String> LIFECYCLE_TYPES = Set.of(
            "payment.authorized.v1", "payment.captured.v1", "payment.voided.v1",
            "payment.declined.v1", "payment.review.v1");
    /**
     * Events that record money going back after a capture.
     *
     * <p>They carry a payment whose status is still CAPTURED, because a return does not move the
     * payment's state. What distinguishes them is the return block, which is required here and
     * forbidden on a lifecycle event: that is the only thing that makes two partial refunds of the
     * same payment legible as two operations rather than one repeated snapshot.
     */
    public static final Set<String> RETURN_TYPES = Set.of("payment.refunded.v1", "payment.reversed.v1");
    public static final Set<String> SUPPORTED_TYPES =
            Set.copyOf(Stream.concat(LIFECYCLE_TYPES.stream(), RETURN_TYPES.stream()).toList());
    private static final Set<String> RETURN_OPERATION_TYPES = Set.of("REFUND", "REVERSAL");
    /**
     * Which operation type each return event is allowed to carry.
     *
     * <p>One-to-one on purpose. An event type and the operation inside it are two statements about the
     * same thing, so a consumer that accepts them disagreeing has to pick one to believe, and either
     * choice is a guess about money.
     */
    private static final java.util.Map<String, String> OPERATION_TYPE_FOR_EVENT = java.util.Map.of(
            "payment.refunded.v1", "REFUND",
            "payment.reversed.v1", "REVERSAL");
    /** Mirrors payment_returns.reason varchar(140). */
    private static final int MAX_RETURN_REASON = 140;
    private static final Set<String> RISK_OUTCOMES = Set.of("APPROVE", "REVIEW", "DECLINE");
    private static final Set<String> CURRENCIES = Set.of("CAD", "USD");
    /** The lifecycle states a payment can be in, and the only values the projection column accepts. */
    private static final Set<String> PAYMENT_STATUSES = Set.of(
            "AUTHORIZED", "CAPTURED", "VOIDED", "DECLINED", "REVIEW");
    private static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
    /** Mirrors policy_version varchar(64) in the consumer tables. */
    private static final java.util.regex.Pattern POLICY_VERSION = java.util.regex.Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
    /** Mirrors failure_code varchar(64). */
    private static final java.util.regex.Pattern FAILURE_CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** Reason codes come from rule codes, which are uppercase identifiers of at most 64 characters. */
    private static final java.util.regex.Pattern REASON_CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int MAX_REASON_DESCRIPTION = 512;

    private final ObjectMapper reader;

    public EventContract(ObjectMapper base) {
        this.reader = base.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** A validated envelope plus a stable fingerprint of the exact bytes delivered. */
    public record Parsed(EventEnvelope envelope, String fingerprint) {}

    public Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new EventContractException("MALFORMED", "Record value was empty");
        }
        EventEnvelope envelope;
        try {
            envelope = reader.readValue(raw, EventEnvelope.class);
        } catch (Exception unreadable) {
            throw new EventContractException("MALFORMED",
                    "Record value is not a readable event envelope: " + unreadable.getClass().getSimpleName(), unreadable);
        }
        if (envelope == null) {
            throw new EventContractException("MALFORMED", "Record value deserialized to null");
        }
        if (envelope.schemaVersion() != EventEnvelope.SUPPORTED_SCHEMA_VERSION) {
            throw new EventContractException("UNSUPPORTED_SCHEMA",
                    "Schema version " + envelope.schemaVersion() + " is not supported; this consumer supports "
                            + EventEnvelope.SUPPORTED_SCHEMA_VERSION);
        }
        if (envelope.eventType() == null || !SUPPORTED_TYPES.contains(envelope.eventType())) {
            throw new EventContractException("UNSUPPORTED_TYPE", "Event type is not supported: " + envelope.eventType());
        }
        require(envelope.eventId() != null, "eventId is required");
        require(envelope.aggregateId() != null, "aggregateId is required");
        require(EventEnvelope.PAYMENT_AGGREGATE.equals(envelope.aggregateType()),
                "aggregateType must be " + EventEnvelope.PAYMENT_AGGREGATE);
        require(envelope.aggregateSequence() > 0, "aggregateSequence must be positive");
        require(envelope.merchantId() != null && envelope.merchantId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                "merchantId is required and must be an identifier");
        require(envelope.occurredAt() != null, "occurredAt is required");
        EventEnvelope.Payment payment = envelope.payment();
        require(payment != null, "payment snapshot is required");
        require(payment.id() != null && payment.id().equals(envelope.aggregateId()),
                "payment id must equal the envelope aggregateId");
        require(payment.accountId() != null, "payment accountId is required");
        require(payment.amountMinor() > 0 && payment.amountMinor() <= MAX_AMOUNT_MINOR,
                "payment amountMinor is out of range");
        // Canonical form is required rather than normalised into. Upper-casing the value here would
        // leave the envelope disagreeing with the bytes that were delivered, fingerprinted, and
        // deduplicated on, and a consumer must not rewrite an immutable event it received. Accepting
        // a non-canonical value instead pushed the rejection down to a column CHECK, where it
        // surfaced as a storage error and was retried forever.
        require(payment.currency() != null && CURRENCIES.contains(payment.currency()),
                "payment currency must be one of " + CURRENCIES.stream().sorted().toList() + " in canonical upper case");
        require(payment.country() != null && payment.country().matches("[A-Z]{2}"),
                "payment country must be two upper-case ASCII letters");
        require(payment.status() != null && PAYMENT_STATUSES.contains(payment.status()),
                "payment status must be one of " + PAYMENT_STATUSES.stream().sorted().toList());
        require(payment.failureCode() == null || FAILURE_CODE.matcher(payment.failureCode()).matches(),
                "payment failureCode must be an upper-case identifier of at most 64 characters");
        require(payment.createdAt() != null && payment.updatedAt() != null, "payment timestamps are required");
        EventEnvelope.Decision decision = payment.decision();
        require(decision != null, "payment decision is required");
        require(decision.outcome() != null && RISK_OUTCOMES.contains(decision.outcome()),
                "risk decision outcome must be APPROVE, REVIEW or DECLINE");
        require(decision.score() >= 0 && decision.score() <= 100, "risk score must be between 0 and 100");
        require(decision.ruleSetVersion() != null && POLICY_VERSION.matcher(decision.ruleSetVersion()).matches(),
                "policy version must be an identifier of at most 64 characters");
        require(decision.reasons() != null && !decision.reasons().isEmpty(), "decision reasons are required");
        require(payment.capturedAmountMinor() == null
                        || (payment.capturedAmountMinor() > 0 && payment.capturedAmountMinor() <= MAX_AMOUNT_MINOR),
                "payment capturedAmountMinor is out of range");
        require(payment.returnedAmountMinor() >= 0 && payment.returnedAmountMinor() <= MAX_AMOUNT_MINOR,
                "payment returnedAmountMinor is out of range");
        // Absent means "this event did not state it", which is what every event written before returns
        // existed looks like. Only a stated capture amount can be compared against a stated return
        // total, so the cap is checked when both are present rather than assuming zero for a missing
        // one and rejecting perfectly good history.
        require(payment.capturedAmountMinor() == null
                        || payment.returnedAmountMinor() <= payment.capturedAmountMinor(),
                "payment returnedAmountMinor cannot exceed capturedAmountMinor");
        for (EventEnvelope.Reason reason : decision.reasons()) {
            require(reason != null, "a decision reason cannot be null");
            require(reason.code() != null && REASON_CODE.matcher(reason.code()).matches(),
                    "a decision reason code must be an upper-case identifier of at most 64 characters");
            require(reason.description() != null && !reason.description().isBlank()
                            && reason.description().length() <= MAX_REASON_DESCRIPTION,
                    "a decision reason description must contain 1 to " + MAX_REASON_DESCRIPTION + " characters");
            require(reason.scoreContribution() >= 0 && reason.scoreContribution() <= 100,
                    "a decision reason contribution must be between 0 and 100");
        }
        validateReturnOperation(envelope, payment);
        return new Parsed(envelope, fingerprint(raw));
    }

    /**
     * A return event must name its return operation; a lifecycle event must not.
     *
     * <p>Requiring it is what makes repeated partial refunds distinguishable. Forbidding it on a
     * lifecycle event is the other half: an authorization that arrived carrying a return block would
     * mean the producer and this consumer disagree about what the event is, and applying either
     * reading would be a guess.
     */
    private static void validateReturnOperation(EventEnvelope envelope, EventEnvelope.Payment payment) {
        EventEnvelope.Return operation = envelope.returnOperation();
        if (!RETURN_TYPES.contains(envelope.eventType())) {
            require(operation == null, "a lifecycle event must not carry a return operation");
            return;
        }
        require(operation != null, "a return event must carry its return operation");
        require(operation.id() != null, "return id is required");
        require(operation.type() != null && RETURN_OPERATION_TYPES.contains(operation.type()),
                "return type must be one of " + RETURN_OPERATION_TYPES.stream().sorted().toList());
        // The event type and the operation type are two statements about the same thing, and this is
        // where they are required to agree. Checking the operation type only against the set of known
        // values let a partial refund arrive labelled payment.reversed.v1 - and because the
        // full-capture rule below keyed off the nested type, that mislabelling also skipped the one
        // check that would have caught it.
        require(OPERATION_TYPE_FOR_EVENT.get(envelope.eventType()).equals(operation.type()),
                "return type " + operation.type() + " must match the event type " + envelope.eventType()
                        + ", which carries " + OPERATION_TYPE_FOR_EVENT.get(envelope.eventType()));
        require(operation.amountMinor() > 0 && operation.amountMinor() <= MAX_AMOUNT_MINOR,
                "return amountMinor is out of range");
        require(operation.currency() != null && CURRENCIES.contains(operation.currency()),
                "return currency must be one of " + CURRENCIES.stream().sorted().toList() + " in canonical upper case");
        require(operation.currency().equals(payment.currency()),
                "return currency must match the payment currency");
        require(operation.reason() == null || (!operation.reason().isBlank()
                        && operation.reason().length() <= MAX_RETURN_REASON),
                "a return reason, when present, must contain 1 to " + MAX_RETURN_REASON + " characters");
        require(operation.sequenceNumber() > 0, "return sequenceNumber must be positive");
        require(operation.occurredAt() != null, "return occurredAt is required");
        // The event states both the operation's amount and the running total it produced, so the two
        // can be checked against each other here rather than taken on trust by whatever applies them.
        require(payment.returnedAmountMinor() >= operation.amountMinor(),
                "returnedAmountMinor must include this return");
        // A return only exists after a capture, and this consumer is entitled to refuse an event that
        // says otherwise rather than project a refund onto a payment that never took money.
        require("CAPTURED".equals(payment.status()), "a return event must carry a captured payment");
        require(payment.capturedAmountMinor() != null, "a return event must state the captured amount");
        // REVERSAL means the whole capture came back. Anything less is a refund wearing the wrong name.
        // Keyed off the event type, which the correspondence above has already tied to the operation
        // type: reading the nested type here is what let a mislabelled event decide for itself whether
        // this rule applied to it.
        require(!"payment.reversed.v1".equals(envelope.eventType())
                        || operation.amountMinor() == payment.capturedAmountMinor(),
                "a reversal must return the full captured amount");
    }

    public static String fingerprint(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new EventContractException("MALFORMED", message);
        }
    }
}
