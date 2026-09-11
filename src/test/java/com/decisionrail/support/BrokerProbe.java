package com.decisionrail.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Direct broker access for tests: inspecting what was actually published, and injecting
 * records the application itself would never produce.
 *
 * <p>Infrastructure behaviour is verified against a real broker. If the broker is missing the
 * suite fails loudly here rather than skipping the checks and reporting success.
 */
public final class BrokerProbe {
    private BrokerProbe() {}

    public static String bootstrapServers() {
        String configured = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        return configured == null || configured.isBlank() ? "127.0.0.1:19092" : configured;
    }

    /** Fails the test run when the dedicated test broker is unreachable. */
    public static void requireReachable() {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "15000"))) {
            if (admin.describeCluster().nodes().get(20, TimeUnit.SECONDS).isEmpty()) {
                throw new AssertionError("Test broker reported no nodes at " + bootstrapServers());
            }
        } catch (Exception unreachable) {
            throw new AssertionError("""
                    The dedicated test broker at %s is not reachable, so event delivery behaviour \
                    cannot be verified. Start it with: docker compose -f compose.test.yaml up -d. \
                    These checks are never skipped; an unavailable broker is a failed run."""
                    .formatted(bootstrapServers()), unreachable);
        }
    }

    /** Creates the topic up front so a first send cannot race broker-side auto-creation. */
    public static void ensureTopic(String name, int partitions) {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(name, partitions, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (!(failure.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw new AssertionError("Could not create test topic " + name, failure);
            }
        }
    }

    public static AdminClient admin() {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000"));
    }

    /** Reads every record currently on the topic from the beginning. */
    public static List<ConsumerRecord<String, String>> drain(String topic, Duration budget) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + java.util.UUID.randomUUID());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + budget.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                polled.forEach(collected::add);
            }
        }
        return collected;
    }

    /**
     * Publishes to one explicit partition, so a test can prove that a refused record does not block
     * the records queued behind it on the same partition.
     */
    public static void publishRawToPartition(String topic, int partition, String key, String value) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(topic, partition, key, value)).get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("Could not publish a raw test record to " + topic + " partition " + partition, failure);
        }
    }

    /** Committed offset for one partition of a consumer group, or -1 when nothing is committed yet. */
    public static long committedOffset(String group, String topic, int partition) {
        try (AdminClient admin = admin()) {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS);
            var metadata = offsets.get(new org.apache.kafka.common.TopicPartition(topic, partition));
            return metadata == null ? -1L : metadata.offset();
        } catch (Exception failure) {
            throw new AssertionError("Could not read committed offsets for group " + group, failure);
        }
    }

    /** Publishes a raw value so tests can deliver records the application would never emit. */
    public static void publishRaw(String topic, String key, String value) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("Could not publish a raw test record to " + topic, failure);
        }
    }
}
