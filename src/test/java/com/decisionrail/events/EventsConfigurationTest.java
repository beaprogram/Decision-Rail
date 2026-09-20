package com.decisionrail.events;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real factory methods and Boot's admin configuration without creating Kafka clients.
 * Connection values are inert examples, and no database, broker, certificate or secret is needed.
 */
class EventsConfigurationTest {
    private static final String JAAS = "org.apache.kafka.common.security.scram.ScramLoginModule required "
            + "username=\"configuration-test\" password=\"not-a-real-secret\";";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(FactoriesOnly.class)
            .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:19092",
                    "spring.kafka.admin.auto-create=false");

    @Test
    void localConfigurationKeepsPlaintextAndTheExistingTopicShape() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var producer = producerConfig(context);
            var consumer = consumerConfig(context);
            var admin = context.getBean(KafkaAdmin.class).getConfigurationProperties();
            for (var config : List.of(producer, consumer, admin)) {
                assertThat(config).containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
                        List.of("127.0.0.1:19092"));
            }
            assertThat(ProducerConfig.configDef().parse(producer))
                    .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            assertThat(ConsumerConfig.configDef().parse(consumer))
                    .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            assertThat(AdminClientConfig.configDef().parse(admin))
                    .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            var topic = new EventsConfiguration().paymentEventsTopic(context.getBean(DeliveryProperties.class));
            assertThat(topic.name()).isEqualTo("decisionrail.payments.v1");
            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        });
    }

    @Test
    void managedBrokerSecurityReachesProducerConsumerAndAdminWithHostnameVerification() {
        contextRunner.withPropertyValues(
                "spring.kafka.bootstrap-servers=broker.example.invalid:10466",
                "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.properties[sasl.mechanism]=SCRAM-SHA-256",
                "spring.kafka.properties[sasl.jaas.config]=" + JAAS,
                "spring.kafka.ssl.trust-store-type=PEM",
                // A configuration value only: clients are never constructed and no CA is parsed.
                "spring.kafka.properties[ssl.truststore.certificates]=configuration-only-test-ca",
                "spring.kafka.producer.properties[compression.type]=gzip",
                "spring.kafka.consumer.properties[fetch.min.bytes]=64")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var producer = producerConfig(context);
                    var consumer = consumerConfig(context);
                    var admin = context.getBean(KafkaAdmin.class).getConfigurationProperties();
                    for (var config : List.of(producer, consumer, admin)) {
                        assertThat(config)
                                .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
                                        List.of("broker.example.invalid:10466"))
                                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                                .containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256")
                                .containsEntry(SaslConfigs.SASL_JAAS_CONFIG, JAAS)
                                .containsEntry(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM")
                                .containsEntry(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG,
                                        "configuration-only-test-ca");
                    }
                    // Parsing config definitions resolves Kafka's defaults without a network client.
                    // Managed connections must not require disabling TLS hostname verification.
                    assertThat(ProducerConfig.configDef().parse(producer))
                            .containsEntry(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
                    assertThat(ConsumerConfig.configDef().parse(consumer))
                            .containsEntry(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
                    assertThat(AdminClientConfig.configDef().parse(admin))
                            .containsEntry(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
                    assertThat(producer).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
                    assertThat(consumer).containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "64");
                });
    }

    @Test
    void externalProducerSettingsCannotWeakenDeliveryGuaranteesOrExpandItsBounds() {
        contextRunner.withPropertyValues(
                "spring.kafka.producer.acks=0",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
                "spring.kafka.producer.properties[enable.idempotence]=false",
                "spring.kafka.producer.properties[max.in.flight.requests.per.connection]=5",
                "spring.kafka.producer.properties[delivery.timeout.ms]=120000",
                "spring.kafka.producer.properties[request.timeout.ms]=30000",
                "spring.kafka.producer.properties[max.block.ms]=60000",
                "spring.kafka.producer.properties[retry.backoff.ms]=9999",
                "spring.kafka.producer.properties[linger.ms]=9999",
                "spring.kafka.producer.buffer-memory=64MB",
                "spring.kafka.producer.client-id=external-producer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(producerConfig(context))
                            .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                            .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                            .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1)
                            .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                            .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3333)
                            .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1664)
                            .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3333)
                            .containsEntry(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100)
                            .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 5)
                            .containsEntry(ProducerConfig.BUFFER_MEMORY_CONFIG, 16 * 1024 * 1024)
                            .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "decisionrail-outbox");
                });
    }

    @Test
    void externalConsumerSettingsCannotCommitOffsetsEarlyOrWeakenIsolationAndBounds() {
        contextRunner.withPropertyValues(
                "spring.kafka.consumer.enable-auto-commit=true",
                "spring.kafka.consumer.auto-offset-reset=latest",
                "spring.kafka.consumer.max-poll-records=10000",
                "spring.kafka.consumer.isolation-level=read_uncommitted",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "spring.kafka.consumer.properties[session.timeout.ms]=60000",
                "spring.kafka.consumer.properties[request.timeout.ms]=120000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(consumerConfig(context))
                            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                            .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50)
                            .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                            .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                            .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                            .containsEntry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000)
                            .containsEntry(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15000);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPRING_KAFKA_ADMIN_AUTOCREATE", "SPRING_KAFKA_ADMIN_AUTO_CREATE"})
    void deploymentEnvironmentCanDisableTopicCreationForAPrecreatedTwoPartitionTopic(String name) {
        contextRunner.withPropertyValues("spring.kafka.admin.auto-create=true")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(name, "false"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(KafkaProperties.class).getAdmin().isAutoCreate()).isFalse();
                    // KafkaAdmin has a setter but no getter; check the actual Boot-created bean too.
                    assertThat(ReflectionTestUtils.getField(context.getBean(KafkaAdmin.class), "autoCreate"))
                            .isEqualTo(false);
                });
    }

    private static Map<String, Object> producerConfig(AssertableApplicationContext context) {
        DefaultKafkaProducerFactory<?, ?> factory = context.getBean(DefaultKafkaProducerFactory.class);
        return factory.getConfigurationProperties();
    }

    private static Map<String, Object> consumerConfig(AssertableApplicationContext context) {
        ConsumerFactory<?, ?> factory = context.getBean(ConsumerFactory.class);
        return factory.getConfigurationProperties();
    }

    /** Only the client factory beans: the full EventsConfiguration also requires database stores. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeliveryProperties.class)
    static class FactoriesOnly {
        private final EventsConfiguration events = new EventsConfiguration();

        @Bean
        DefaultKafkaProducerFactory<String, String> paymentEventProducerFactory(
                KafkaProperties kafka, DeliveryProperties delivery) {
            return events.paymentEventProducerFactory(kafka, delivery);
        }

        @Bean
        ConsumerFactory<String, String> paymentEventConsumerFactory(KafkaProperties kafka) {
            return events.paymentEventConsumerFactory(kafka);
        }

        @Bean
        KafkaTemplate<String, String> paymentEventKafkaTemplate(DefaultKafkaProducerFactory<String, String> factory) {
            return events.paymentEventKafkaTemplate(factory);
        }
    }
}
