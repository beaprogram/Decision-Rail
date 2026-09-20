package com.decisionrail.events;

import com.decisionrail.resilience.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import com.decisionrail.telemetry.Cached;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Broker client configuration and the published retry budget.
 *
 * <h2>Total retry budget for one event</h2>
 * Two layers retry, and the documented budget is their product, not either one alone.
 * <ul>
 *   <li><b>Kafka client:</b> {@code delivery.timeout.ms} caps everything the producer does
 *       for a single send, including its own internal retries and {@code request.timeout.ms}
 *       round trips. It is set below the application send deadline so the client gives up
 *       first and reports a definite failure rather than leaving the dispatcher to time out
 *       on an attempt that is still in flight.</li>
 *   <li><b>Application:</b> the dispatcher makes at most {@code app.events.dispatcher.max-attempts}
 *       attempts per event, each separated by exponential backoff with full jitter, after
 *       which the event is terminally FAILED and waits for an operator redrive.</li>
 * </ul>
 * With the defaults - a 4s client delivery timeout, a 5s application deadline, 8 attempts,
 * and backoff from 200ms to a 30s ceiling - one event spends at most roughly 40s of send
 * time and about 2 minutes of waiting before it stops retrying on its own. The circuit
 * breaker shortens this considerably during a sustained outage, because short-circuited
 * attempts cost no broker round trip at all.
 */
@Configuration
@EnableConfigurationProperties(DeliveryProperties.class)
@EnableScheduling
public class EventsConfiguration {

    /**
     * Guards the broker boundary used by the outbox dispatcher. A single shared instance is
     * correct: the thing being protected is one dependency, not one event.
     */
    @Bean
    CircuitBreaker brokerBreaker(DeliveryProperties properties, Clock clock) {
        return new CircuitBreaker("kafka-publish", properties.breaker().failureThreshold(),
                properties.breaker().openDuration(), properties.breaker().probeSuccessesToClose(), clock);
    }

    @Bean
    NewTopic paymentEventsTopic(DeliveryProperties properties) {
        // Single-node development broker: one replica. Not a highly available configuration.
        return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultKafkaProducerFactory<String, String> paymentEventProducerFactory(
            KafkaProperties kafkaProperties, DeliveryProperties properties) {
        // Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms, and this
        // application requires the client to give up before its own send deadline so a failure
        // is reported definitively instead of abandoned while still in flight. Both constraints
        // are derived here rather than hand-tuned, so changing the deadline cannot produce an
        // invalid producer configuration.
        final int lingerMillis = 5;
        int clientDeliveryTimeoutMillis = (int) (properties.dispatcher().sendTimeout().toMillis() * 2 / 3);
        int requestTimeoutMillis = Math.max(500, (clientDeliveryTimeoutMillis - lingerMillis) / 2);
        if (clientDeliveryTimeoutMillis < lingerMillis + requestTimeoutMillis) {
            throw new IllegalStateException("Derived producer timeouts are inconsistent; increase app.events.dispatcher.send-timeout");
        }
        // Keep Boot's connection settings (including TLS and SASL) for managed brokers. Apply
        // the delivery invariants afterwards so external settings cannot weaken acknowledgements,
        // ordering or deadlines. With no security settings, local PLAINTEXT behavior is unchanged.
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // acks=all with idempotence: an acknowledgement means the record is durable on the
        // partition leader's replica set, and a client-side retry cannot duplicate it.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, clientDeliveryTimeoutMillis);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMillis);
        // Bounded: a send must never block a dispatcher thread waiting for metadata or buffer.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, clientDeliveryTimeoutMillis);
        config.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        config.put(ProducerConfig.LINGER_MS_CONFIG, lingerMillis);
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 16 * 1024 * 1024);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "decisionrail-outbox");
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, String> paymentEventKafkaTemplate(DefaultKafkaProducerFactory<String, String> factory) {
        return new KafkaTemplate<>(factory);
    }

    @Bean
    ConsumerFactory<String, String> paymentEventConsumerFactory(
            KafkaProperties kafkaProperties) {
        // Share the same Boot connection/security configuration as the producer and auto-configured
        // KafkaAdmin, while retaining the consumer's transaction and offset guarantees below.
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Offsets are committed by the application after its database transaction commits.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> paymentEventListenerFactory(
            ConsumerFactory<String, String> paymentEventConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(paymentEventConsumerFactory);
        // Never started by the context lifecycle, whatever auto-startup says. Building a consumer
        // resolves bootstrap.servers, and an unresolvable name throws out of the lifecycle processor
        // and fails the whole application - so a restart while the broker's name was gone took the
        // payment API down with it. ListenerStarter starts them afterwards instead, honouring that
        // same property, and retries until the broker is reachable.
        factory.setAutoStartup(false);
        // One consumer thread keeps each partition's records strictly sequential.
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // An unbounded, capped backoff means a database outage blocks the partition and keeps
        // retrying rather than skipping events. Contract violations never reach this handler:
        // the consumer quarantines and acknowledges them itself.
        ExponentialBackOff redeliveryBackOff = new ExponentialBackOff(500, 2.0);
        redeliveryBackOff.setMaxInterval(10_000);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(redeliveryBackOff);
        errorHandler.setAckAfterHandle(false);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /** Operational visibility for the asynchronous path. No per-payment labels: cardinality stays bounded. */
    @Bean
    DeliveryMetrics deliveryMetrics(OutboxStore outbox, InboxStore inbox, CircuitBreaker brokerBreaker,
                                    DeliveryProperties properties, Clock clock, MeterRegistry registry) {
        return new DeliveryMetrics(outbox, inbox, brokerBreaker, properties, clock, registry);
    }

    /**
     * Registers gauges for backlog and breaker state.
     *
     * <p>The backlog figures come from one cached aggregate rather than a {@code count(*)} per status
     * per scrape. Each of those was a full count over a table that grows for the life of the
     * deployment, so the cost of watching the system used to rise with its own history. The cached
     * value is at most {@link #BACKLOG_FRESHNESS} old, which is stated in the metric catalogue, and it
     * reports no data rather than zero when the database cannot answer.
     */
    public static class DeliveryMetrics {
        /** How stale a backlog reading may be. Shorter than any sensible alert window. */
        public static final Duration BACKLOG_FRESHNESS = Duration.ofSeconds(5);

        public DeliveryMetrics(OutboxStore outbox, InboxStore inbox, CircuitBreaker breaker,
                               DeliveryProperties properties, Clock clock, MeterRegistry registry) {
            Cached<OutboxBacklog> backlog = new Cached<>("outbox.backlog", BACKLOG_FRESHNESS, clock,
                    () -> outbox.backlog(clock.instant(), breaker.state().name()));
            for (String status : new String[]{"PENDING", "CLAIMED", "PUBLISHED", "FAILED"}) {
                Gauge.builder("decisionrail.outbox.backlog",
                                () -> backlog.reading(current -> current.countsByStatus().getOrDefault(status, 0L)))
                        .description("Outbox rows by delivery status, at most " + BACKLOG_FRESHNESS.toSeconds() + "s old")
                        .tag("status", status)
                        .register(registry);
            }
            Gauge.builder("decisionrail.outbox.backlog.age",
                            () -> backlog.reading(OutboxBacklog::oldestPendingAgeSeconds))
                    .description("Age of the oldest undelivered outbox event, measured from its occurred_at")
                    .baseUnit("seconds")
                    .register(registry);
            Gauge.builder("decisionrail.outbox.blocked.payments",
                            () -> backlog.reading(OutboxBacklog::blockedPaymentCount))
                    .description("Payments whose event stream is blocked by a terminally failed event")
                    .register(registry);
            Gauge.builder("decisionrail.broker.breaker.state", breaker::stateCode)
                    .description("Broker circuit breaker state: 0 closed, 1 half-open, 2 open")
                    .register(registry);
            Cached<Long> quarantined = new Cached<>("consumer.quarantine", BACKLOG_FRESHNESS, clock,
                    () -> inbox.quarantineCount(properties.projectionGroup()));
            Gauge.builder("decisionrail.consumer.quarantine.size", () -> quarantined.reading(Long::doubleValue))
                    .description("Records the projection consumer refused to apply")
                    .tag("group", properties.projectionGroup())
                    .register(registry);
        }
    }
}
