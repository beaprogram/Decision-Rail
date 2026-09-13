package com.decisionrail.events;

import com.decisionrail.resilience.CircuitBreaker;
import com.decisionrail.telemetry.DeliveryTracing;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes one claimed outbox event and waits for the broker to acknowledge it.
 *
 * <p>The wait is the point. {@code KafkaTemplate.send} returns as soon as the record is
 * buffered, which says nothing about durability. This class blocks on the returned future
 * up to the configured send deadline and treats anything else - failure, timeout,
 * interruption - as a send that did not happen. The outbox row is only marked published
 * after a real acknowledgement carrying a partition and offset.
 *
 * <p>Partitioning uses the payment id as the record key, so one payment's events land on one
 * partition. That is necessary for a consumer to observe them in order, but it is not what
 * guarantees order: the dispatcher's single-in-flight-per-payment rule does that.
 */
@Component
public class EventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final DeliveryProperties properties;
    private final CircuitBreaker breaker;
    private final DeliveryFaults faults;
    private final MeterRegistry metrics;
    private final DeliveryTracing tracing;

    public EventPublisher(KafkaTemplate<String, String> kafka, DeliveryProperties properties,
                          CircuitBreaker brokerBreaker, DeliveryFaults faults, MeterRegistry metrics,
                          DeliveryTracing tracing) {
        this.kafka = kafka;
        this.properties = properties;
        this.breaker = brokerBreaker;
        this.faults = faults;
        this.metrics = metrics;
        this.tracing = tracing;
    }

    /** Broker-confirmed placement of a record. */
    public record Acknowledgement(int partition, long offset) {}

    public Acknowledgement publish(ClaimedEvent event) {
        if (!breaker.tryAcquire()) {
            metrics.counter("decisionrail.outbox.publish.short_circuited").increment();
            throw new BrokerSendException("Broker circuit breaker is open; send not attempted", true, true, null);
        }
        metrics.counter("decisionrail.outbox.publish.attempts").increment();
        try {
            faults.beforeSend(event.aggregateId());
            Acknowledgement acknowledgement = awaitAcknowledgement(event);
            breaker.recordSuccess();
            metrics.counter("decisionrail.outbox.publish.acknowledged").increment();
            return acknowledgement;
        } catch (BrokerSendException failure) {
            if (failure.retryable() && !failure.shortCircuited()) {
                breaker.recordFailure();
            } else {
                // A malformed event is a defect in our own data, not a dependency fault.
                breaker.recordSuccess();
            }
            metrics.counter("decisionrail.outbox.publish.failures", "retryable", Boolean.toString(failure.retryable())).increment();
            throw failure;
        }
    }

    private Acknowledgement awaitAcknowledgement(ClaimedEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                properties.topic(), null, event.partitionKey(), event.payload());
        record.headers()
                .add(new RecordHeader("eventId", event.id().toString().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("eventType", event.eventType().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("schemaVersion", Integer.toString(event.schemaVersion()).getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("merchantId", event.merchantId().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("aggregateSequence", Long.toString(event.aggregateSequence()).getBytes(StandardCharsets.UTF_8)));
        // Trace context travels as a header, never in the payload: the payload bytes are what a
        // consumer fingerprints for deduplication, so putting correlation there would change event
        // identity on every attempt and invalidate every fingerprint already recorded.
        tracing.inject(record.headers());

        CompletableFuture<SendResult<String, String>> pending;
        try {
            pending = kafka.send(record);
        } catch (RuntimeException rejected) {
            // Buffer exhaustion or max.block.ms expiry: the record never entered the pipeline.
            throw new BrokerSendException("Producer rejected the record before sending: " + rejected.getClass().getSimpleName(), true, rejected);
        }
        long deadlineMillis = properties.dispatcher().sendTimeout().toMillis();
        try {
            SendResult<String, String> result = pending.get(deadlineMillis, TimeUnit.MILLISECONDS);
            var metadata = result.getRecordMetadata();
            if (metadata == null || metadata.offset() < 0) {
                throw new BrokerSendException("Broker acknowledgement did not include a durable offset", true);
            }
            return new Acknowledgement(metadata.partition(), metadata.offset());
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new BrokerSendException("No broker acknowledgement within " + deadlineMillis + "ms", true, timedOut);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BrokerSendException("Interrupted while awaiting broker acknowledgement", true, interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            boolean retryable = !(cause instanceof org.apache.kafka.common.errors.SerializationException
                    || cause instanceof org.apache.kafka.common.errors.RecordTooLargeException
                    || cause instanceof org.apache.kafka.common.errors.InvalidTopicException);
            throw new BrokerSendException("Broker rejected the record: " + cause.getClass().getSimpleName(), retryable, cause);
        }
    }
}
