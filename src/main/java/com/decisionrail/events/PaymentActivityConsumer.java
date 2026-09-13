package com.decisionrail.events;

import com.decisionrail.telemetry.DeliveryTracing;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final DeliveryTracing tracing;

    public PaymentActivityConsumer(EventContract contract, InboxStore inbox, TransactionTemplate transactions,
                                   DeliveryProperties properties, Clock clock, MeterRegistry metrics,
                                   DeliveryTracing tracing) {
        this.contract = contract;
        this.inbox = inbox;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.tracing = tracing;
    }

    @KafkaListener(
            id = "payment-activity-projection",
            topics = "${app.events.topic}",
            groupId = "${app.events.projection-group}",
            containerFactory = "paymentEventListenerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String group = properties.projectionGroup();
        // Continues the publisher's trace when the header is there, and starts a fresh one when it is
        // not, which is what an event written before correlation existed produces. The scope closes on
        // every path, including quarantine, so nothing leaks onto the next record this thread polls.
        try (DeliveryTracing.Scope span = tracing.consume(record.headers(), group, "consumer.project")) {
            Timer.Sample sample = Timer.start(metrics);
            String outcome = "failed";
            try {
                outcome = consumeTraced(group, record, span);
                // Reached only when the database transaction committed or the record was quarantined.
                //
                // Deliberately not in the finally below. Acknowledging here is what makes the offset
                // move, and a storage failure that prevents even the quarantine record from being
                // written must leave the offset where it is so the record is redelivered. Putting this
                // in a finally would acknowledge work that was never recorded, which turns a
                // recoverable outage into lost events.
                acknowledgment.acknowledge();
            } finally {
                // The timer does belong in a finally: a failed attempt still took time, and its
                // duration is recorded under its own outcome rather than disappearing.
                sample.stop(Timer.builder("decisionrail.consumer.processing.duration")
                        .description("Time to process one record, from receipt to committed effect or quarantine")
                        .tag("group", group)
                        .tag("outcome", outcome)
                        .publishPercentileHistogram()
                        .register(metrics));
            }
        }
    }

    private String consumeTraced(String group, ConsumerRecord<String, String> record, DeliveryTracing.Scope span) {
        EventContract.Parsed parsed;
        try {
            parsed = contract.parse(record.value());
        } catch (EventContractException rejected) {
            span.failed(rejected);
            quarantine(group, rejected, null, record);
            return "quarantined";
        }
        span.tag("decisionrail.event_type", parsed.envelope().eventType());
        try {
            return apply(group, parsed, record);
        } catch (EventContractException rejected) {
            span.failed(rejected);
            quarantine(group, rejected, parsed.envelope().eventId(), record);
            return "quarantined";
        }
    }

    private String apply(String group, EventContract.Parsed parsed, ConsumerRecord<String, String> record) {
        // What actually happened, decided inside the transaction and read after it commits. A
        // duplicate delivery and a newly applied effect are different facts and are never merged.
        java.util.concurrent.atomic.AtomicReference<String> outcome = new java.util.concurrent.atomic.AtomicReference<>("applied");
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
                    outcome.set("duplicate");
                    log.debug("Ignoring duplicate delivery of event {}", envelope.eventId());
                }
                case FIRST_DELIVERY -> {
                    if (inbox.applyToProjection(envelope, clock.instant())) {
                        metrics.counter("decisionrail.consumer.applied", "group", group).increment();
                        outcome.set("applied");
                    } else {
                        metrics.counter("decisionrail.consumer.out_of_order", "group", group).increment();
                        outcome.set("out_of_order");
                        log.warn("Event {} carried sequence {} which is not newer than the projection; recorded but not applied",
                                envelope.eventId(), envelope.aggregateSequence());
                    }
                }
            }
        });
        return outcome.get();
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
