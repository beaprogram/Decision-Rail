package com.decisionrail.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the merchant-scoped payment activity projection from delivered events.
 *
 * <p>Ordering of the three steps is the whole design:
 * <ol>
 *   <li>the deduplication record and the projection change commit in one PostgreSQL
 *       transaction, so the read model can never disagree with what was marked consumed;</li>
 *   <li>the Kafka offset is acknowledged only after that commit returns;</li>
 *   <li>if the commit fails, the offset is not acknowledged and the exception propagates so
 *       the container re-delivers instead of advancing past unprocessed work.</li>
 * </ol>
 * A crash between the commit and the acknowledgement therefore replays the record, and the
 * deduplication record makes the replay a no-op. That is at-least-once delivery with
 * idempotent effects. It is not exactly-once processing across PostgreSQL and Kafka, and no
 * arrangement of these two systems without a shared transaction coordinator would be.
 *
 * <p>This consumer never debits a balance, writes a ledger entry, or changes a decision. It
 * only maintains a read model.
 */
@Component
public class PaymentActivityConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentActivityConsumer.class);

    private final EventContract contract;
    private final InboxStore inbox;
    private final TransactionTemplate transactions;
    private final DeliveryProperties properties;
    private final Clock clock;
    private final MeterRegistry metrics;

    public PaymentActivityConsumer(EventContract contract, InboxStore inbox, TransactionTemplate transactions,
                                   DeliveryProperties properties, Clock clock, MeterRegistry metrics) {
        this.contract = contract;
        this.inbox = inbox;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "payment-activity-projection",
            topics = "${app.events.topic}",
            groupId = "${app.events.projection-group}",
            containerFactory = "paymentEventListenerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String group = properties.projectionGroup();
        EventContract.Parsed parsed;
        try {
            parsed = contract.parse(record.value());
        } catch (EventContractException rejected) {
            quarantine(group, rejected, null, record);
            acknowledgment.acknowledge();
            return;
        }
        try {
            apply(group, parsed, record);
        } catch (EventContractException rejected) {
            quarantine(group, rejected, parsed.envelope().eventId(), record);
        }
        // Reached only when the database transaction committed or the record was quarantined.
        acknowledgment.acknowledge();
    }

    private void apply(String group, EventContract.Parsed parsed, ConsumerRecord<String, String> record) {
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
                case DUPLICATE -> {
                    metrics.counter("decisionrail.consumer.duplicates", "group", group).increment();
                    log.debug("Ignoring duplicate delivery of event {}", envelope.eventId());
                }
                case FIRST_DELIVERY -> {
                    if (inbox.applyToProjection(envelope, clock.instant())) {
                        metrics.counter("decisionrail.consumer.applied", "group", group).increment();
                    } else {
                        metrics.counter("decisionrail.consumer.out_of_order", "group", group).increment();
                        log.warn("Event {} carried sequence {} which is not newer than the projection; recorded but not applied",
                                envelope.eventId(), envelope.aggregateSequence());
                    }
                }
            }
        });
    }

    private void quarantine(String group, EventContractException rejected, java.util.UUID eventId,
                            ConsumerRecord<String, String> record) {
        metrics.counter("decisionrail.consumer.quarantined", "group", group, "reason", rejected.reason()).increment();
        log.warn("Quarantined record at {}-{} offset {} reason={} detail={}",
                record.topic(), record.partition(), record.offset(), rejected.reason(), rejected.getMessage());
        transactions.executeWithoutResult(status -> inbox.quarantine(group, rejected.reason(), eventId,
                record.topic(), record.partition(), record.offset(), rejected.getMessage()));
    }
}
