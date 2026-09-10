package com.decisionrail.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
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
    public static final Set<String> SUPPORTED_TYPES = Set.of(
            "payment.authorized.v1", "payment.captured.v1", "payment.voided.v1",
            "payment.declined.v1", "payment.review.v1");
    private static final Set<String> RISK_OUTCOMES = Set.of("APPROVE", "REVIEW", "DECLINE");
    private static final Set<String> CURRENCIES = Set.of("CAD", "USD");
    private static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;

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
        require(payment.currency() != null && CURRENCIES.contains(payment.currency().toUpperCase(Locale.ROOT)),
                "payment currency is not supported");
        require(payment.country() != null && payment.country().matches("[A-Za-z]{2}"), "payment country must be two letters");
        require(payment.status() != null && !payment.status().isBlank(), "payment status is required");
        require(payment.createdAt() != null && payment.updatedAt() != null, "payment timestamps are required");
        EventEnvelope.Decision decision = payment.decision();
        require(decision != null, "payment decision is required");
        require(decision.outcome() != null && RISK_OUTCOMES.contains(decision.outcome()),
                "risk decision outcome must be APPROVE, REVIEW or DECLINE");
        require(decision.score() >= 0 && decision.score() <= 100, "risk score must be between 0 and 100");
        require(decision.ruleSetVersion() != null && !decision.ruleSetVersion().isBlank(), "policy version is required");
        require(decision.reasons() != null && !decision.reasons().isEmpty(), "decision reasons are required");
        return new Parsed(envelope, fingerprint(raw));
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
