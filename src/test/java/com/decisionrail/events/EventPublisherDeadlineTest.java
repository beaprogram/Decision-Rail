package com.decisionrail.events;

import com.decisionrail.resilience.CircuitBreaker;
import com.decisionrail.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acknowledgement semantics and the send deadline.
 *
 * <p>The central claim under test is that scheduling a send is not success. A producer returns as
 * soon as a record is buffered, so a publisher that treats that as delivery would mark events
 * published that the broker never received. These checks drive the publisher with stub futures so
 * each outcome - never completing, completing without durable metadata, failing permanently,
 * failing transiently, being rejected outright - is exercised exactly.
 */
class EventPublisherDeadlineTest {
    private static final Duration SEND_TIMEOUT = Duration.ofMillis(1_500);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-10T00:00:00Z"));
    private final DeliveryProperties properties = properties();
    private final CircuitBreaker breaker = new CircuitBreaker("test", 2, Duration.ofSeconds(10), 1, clock);
    private final DeliveryFaults faults = new DeliveryFaults(false);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @Test
    void aBufferedSendThatNeverAcknowledgesIsAFailureAtTheDeadline() {
        EventPublisher publisher = publisherReturning(new CompletableFuture<>());

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(BrokerSendException.class)
                .hasMessageContaining("No broker acknowledgement within");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // It waited for the deadline rather than returning early on a buffered send, and it gave up
        // at the deadline rather than blocking a dispatcher thread indefinitely.
        assertThat(elapsed).isGreaterThanOrEqualTo(SEND_TIMEOUT);
        assertThat(elapsed).isLessThan(SEND_TIMEOUT.plusSeconds(2));
        assertThat(counter("decisionrail.outbox.publish.attempts")).isEqualTo(1);
        assertThat(counter("decisionrail.outbox.publish.acknowledged")).isZero();
    }

    @Test
    void anAcknowledgementWithoutADurableOffsetIsNotTreatedAsDelivered() {
        assertThatThrownBy(() -> publisherReturning(CompletableFuture.completedFuture(resultWithOffset(-1L))).publish(event()))
                .isInstanceOf(BrokerSendException.class)
                .hasMessageContaining("durable offset");
    }

    @Test
    void aRealAcknowledgementReturnsThePartitionAndOffsetAndClosesTheBreaker() {
        EventPublisher publisher = publisherReturning(CompletableFuture.completedFuture(resultWithOffset(42L)));

        EventPublisher.Acknowledgement acknowledgement = publisher.publish(event());
        assertThat(acknowledgement.partition()).isEqualTo(1);
        assertThat(acknowledgement.offset()).isEqualTo(42L);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(counter("decisionrail.outbox.publish.acknowledged")).isEqualTo(1);
    }

    @Test
    void aSerializationFailureIsTerminalAndDoesNotCountAgainstTheDependencyBreaker() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new org.apache.kafka.common.errors.SerializationException("bad record"));
        EventPublisher publisher = publisherReturning(failed);

        // Repeated attempts with a defective record must not open the breaker: the dependency is
        // healthy and the fault is in our own data.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> publisher.publish(event()))
                    .isInstanceOf(BrokerSendException.class)
                    .matches(thrown -> !((BrokerSendException) thrown).retryable(), "not retryable");
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(counter("decisionrail.outbox.publish.failures")).isEqualTo(5);
    }

    @Test
    void transientBrokerFailuresAreRetryableAndOpenTheBreakerAtItsThreshold() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new org.apache.kafka.common.errors.NotLeaderOrFollowerException("no leader"));
        EventPublisher publisher = publisherReturning(failed);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> publisher.publish(event()))
                    .isInstanceOf(BrokerSendException.class)
                    .matches(thrown -> ((BrokerSendException) thrown).retryable(), "retryable")
                    .matches(thrown -> !((BrokerSendException) thrown).shortCircuited(), "actually attempted");
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // Once open, the send is short-circuited: no broker call, and flagged so the dispatcher
        // refunds the attempt instead of spending the retry budget on it.
        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(BrokerSendException.class)
                .matches(thrown -> ((BrokerSendException) thrown).shortCircuited(), "short circuited");
        assertThat(counter("decisionrail.outbox.publish.short_circuited")).isEqualTo(1);
    }

    @Test
    void aProducerThatRejectsTheRecordOutrightIsReportedAsANotAttemptedSend() {
        EventPublisher publisher = publisherThrowing(new org.apache.kafka.common.KafkaException("buffer full"));
        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(BrokerSendException.class)
                .hasMessageContaining("before sending");
    }

    // ----- helpers -----

    private EventPublisher publisherReturning(CompletableFuture<SendResult<String, String>> future) {
        return new EventPublisher(new StubTemplate(future, null), properties, breaker, faults, metrics);
    }

    private EventPublisher publisherThrowing(RuntimeException failure) {
        return new EventPublisher(new StubTemplate(null, failure), properties, breaker, faults, metrics);
    }

    /** A template that returns whatever future the test supplies instead of contacting a broker. */
    private static final class StubTemplate extends KafkaTemplate<String, String> {
        private final CompletableFuture<SendResult<String, String>> future;
        private final RuntimeException rejection;

        private StubTemplate(CompletableFuture<SendResult<String, String>> future, RuntimeException rejection) {
            super(new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
            this.future = future;
            this.rejection = rejection;
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            if (rejection != null) throw rejection;
            return future;
        }
    }

    private static SendResult<String, String> resultWithOffset(long offset) {
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("topic", 1), offset, 0, 0L, 0, 0);
        return new SendResult<>(record, metadata);
    }

    private static ClaimedEvent event() {
        UUID paymentId = UUID.randomUUID();
        return new ClaimedEvent(UUID.randomUUID(), paymentId, 1L, "demo-merchant", "payment.authorized.v1",
                1, "{}", paymentId.toString(), Instant.parse("2026-09-10T00:00:00Z"), 1, UUID.randomUUID());
    }

    private static DeliveryProperties properties() {
        return new DeliveryProperties("test.topic", "projection", "shadow",
                new DeliveryProperties.Dispatcher(true, 16, Duration.ofMillis(250), Duration.ofSeconds(30), 8,
                        SEND_TIMEOUT, Duration.ofMillis(200), Duration.ofSeconds(30), 2.0, 2),
                new DeliveryProperties.Breaker(2, Duration.ofSeconds(10), 1),
                new DeliveryProperties.Backlog(Duration.ofSeconds(120), 1_000));
    }

    private double counter(String name) {
        return metrics.find(name).counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }
}
