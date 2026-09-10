package com.decisionrail.shadow;

import com.decisionrail.events.DeliveryProperties;
import com.decisionrail.events.EventContract;
import com.decisionrail.events.EventContractException;
import com.decisionrail.events.EventEnvelope;
import com.decisionrail.events.InboxStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns delivered authorization events into durable shadow work.
 *
 * <p>This runs in its own consumer group, separate from the activity projection. That separation
 * is the point: a shadow enqueue failure must not roll back the projection's transaction or stall
 * its offsets, and shadow evaluation must never be able to interfere with a consumer that has
 * nothing to do with it.
 *
 * <p>Deriving work from committed events rather than from an in-process callback is what makes
 * shadow evaluation survive a restart. A callback registered during an authorization would be lost
 * with the process, and making the authorization wait for it would turn a candidate policy into a
 * synchronous dependency of taking payments.
 *
 * <p>Only authorization-outcome events enqueue work. Capture and void carry the same risk decision
 * the authorization already recorded, so they would add nothing but duplicate evaluations.
 */
@Component
public class ShadowTaskConsumer {
    private static final Logger log = LoggerFactory.getLogger(ShadowTaskConsumer.class);
    /** The three outcomes an authorization can produce. */
    private static final Set<String> AUTHORIZATION_EVENTS = Set.of(
            "payment.authorized.v1", "payment.declined.v1", "payment.review.v1");

    private final EventContract contract;
    private final InboxStore inbox;
    private final ShadowStore shadow;
    private final TransactionTemplate transactions;
    private final DeliveryProperties properties;
    private final MeterRegistry metrics;

    public ShadowTaskConsumer(EventContract contract, InboxStore inbox, ShadowStore shadow,
                              TransactionTemplate transactions, DeliveryProperties properties, MeterRegistry metrics) {
        this.contract = contract;
        this.inbox = inbox;
        this.shadow = shadow;
        this.transactions = transactions;
        this.properties = properties;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "shadow-task-enqueue",
            topics = "${app.events.topic}",
            groupId = "${app.events.shadow-group}",
            containerFactory = "paymentEventListenerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String group = properties.shadowGroup();
        EventContract.Parsed parsed;
        try {
            parsed = contract.parse(record.value());
        } catch (EventContractException rejected) {
            quarantine(group, rejected, null, record);
            acknowledgment.acknowledge();
            return;
        }
        try {
            enqueue(group, parsed, record);
        } catch (EventContractException rejected) {
            quarantine(group, rejected, parsed.envelope().eventId(), record);
        }
        // Only after the database transaction committed, or the record was quarantined.
        acknowledgment.acknowledge();
    }

    private void enqueue(String group, EventContract.Parsed parsed, ConsumerRecord<String, String> record) {
        transactions.executeWithoutResult(status -> {
            EventEnvelope envelope = parsed.envelope();
            if (!inbox.merchantExists(envelope.merchantId())) {
                throw new EventContractException("UNKNOWN_MERCHANT",
                        "Event references a merchant this deployment does not know");
            }
            InboxStore.Claim claim = inbox.claim(group, envelope, parsed.fingerprint(),
                    record.topic(), record.partition(), record.offset());
            switch (claim) {
                case IDENTITY_CONFLICT -> throw new EventContractException("IDENTITY_CONFLICT",
                        "Event id " + envelope.eventId() + " was already consumed with different content");
                case DUPLICATE -> metrics.counter("decisionrail.consumer.duplicates", "group", group).increment();
                case FIRST_DELIVERY -> {
                    if (!AUTHORIZATION_EVENTS.contains(envelope.eventType())) {
                        metrics.counter("decisionrail.shadow.events.ignored").increment();
                        return;
                    }
                    ShadowStore.Settings settings = shadow.settings();
                    if (!settings.enabled() || settings.candidateVersion() == null) {
                        metrics.counter("decisionrail.shadow.events.disabled").increment();
                        return;
                    }
                    // The candidate is pinned here. Changing the setting later does not alter what
                    // an already queued task evaluates.
                    if (shadow.enqueue(envelope, settings.candidateVersion())) {
                        metrics.counter("decisionrail.shadow.tasks.enqueued").increment();
                    }
                }
            }
        });
    }

    private void quarantine(String group, EventContractException rejected, java.util.UUID eventId,
                            ConsumerRecord<String, String> record) {
        metrics.counter("decisionrail.consumer.quarantined", "group", group, "reason", rejected.reason()).increment();
        log.warn("Shadow consumer quarantined record at {}-{} offset {} reason={}",
                record.topic(), record.partition(), record.offset(), rejected.reason());
        transactions.executeWithoutResult(status -> inbox.quarantine(group, rejected.reason(), eventId,
                record.topic(), record.partition(), record.offset(), rejected.getMessage()));
    }
}
