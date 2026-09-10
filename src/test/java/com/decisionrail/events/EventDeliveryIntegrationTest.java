package com.decisionrail.events;

import com.decisionrail.resilience.CircuitBreaker;
import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Event delivery against a real single-node Kafka broker and real PostgreSQL.
 *
 * <p>The dispatcher timer is off in the test profile so each cycle is driven explicitly. That
 * makes the failure windows reproducible: claim, send, and record are separate steps, and a
 * test can interrupt between any two of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventDeliveryIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.delivery." + RUN;
    private static final String GROUP = "test-projection-" + RUN;
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void brokerAndTopics(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        registry.add("spring.kafka.bootstrap-servers", BrokerProbe::bootstrapServers);
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> GROUP);
        registry.add("app.events.shadow-group", () -> GROUP + "-shadow");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxStore outbox;
    @Autowired InboxStore inbox;
    @Autowired EventPublisher publisher;
    @Autowired DeliveryFaults faults;
    @Autowired DeliveryProperties properties;
    @Autowired CircuitBreaker brokerBreaker;
    @Autowired TransactionTemplate transactions;
    @Autowired Clock clock;
    @Autowired MeterRegistry metrics;
    @Autowired KafkaListenerEndpointRegistry listeners;

    private UUID accountId;

    @BeforeEach
    void prepare() {
        faults.clear();
        brokerBreaker.reset();
        accountId = newAccount("demo-merchant", 5_000_000);
    }

    @AfterEach
    void disarmFaults() {
        faults.clear();
        brokerBreaker.reset();
    }

    @Test
    void committedEventsPublishInPaymentOrderAndProjectExactlyOnce() throws Exception {
        UUID payment = authorize(accountId, 2_500);
        capture(payment);
        assertThat(outboxSequences(payment)).containsExactly(1L, 2L);
        List<UUID> originalEventIds = eventIds(payment);

        deliver(payment);

        List<Map<String, Object>> rows = outboxRows(payment);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PUBLISHED");
            assertThat(row.get("published_at")).isNotNull();
            assertThat(row.get("broker_offset")).isNotNull();
            assertThat(row.get("broker_partition")).isNotNull();
        });
        // Identity survives delivery: the published ids are the committed ids.
        assertThat(eventIds(payment)).containsExactlyElementsOf(originalEventIds);

        Waits.until("projection reaches the capture event", BUDGET, () -> {
            Map<String, Object> activity = activityRow(payment);
            return activity != null && "CAPTURED".equals(activity.get("last_status"))
                    && ((Number) activity.get("last_sequence")).longValue() == 2L;
        });
        Map<String, Object> activity = activityRow(payment);
        assertThat(activity.get("merchant_id")).isEqualTo("demo-merchant");
        assertThat(((Number) activity.get("applied_event_count")).intValue()).isEqualTo(2);
        assertThat(activity.get("risk_outcome")).isEqualTo("APPROVE");
        assertThat(inbox.consumedCount(GROUP, payment)).isEqualTo(2);

        // The broker really holds both events, keyed by payment, in sequence order.
        List<JsonNode> delivered = publishedEnvelopes(payment);
        assertThat(delivered).hasSize(2);
        assertThat(delivered.stream().map(node -> node.path("aggregateSequence").asLong()).toList())
                .containsExactly(1L, 2L);
        assertThat(delivered.getFirst().path("merchantId").asText()).isEqualTo("demo-merchant");
        assertThat(delivered.getFirst().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(delivered.getFirst().path("aggregateType").asText()).isEqualTo("payment");

        Map<String, Object> view = readJson("/v1/payments/" + payment + "/activity");
        assertThat(view.get("lastStatus")).isEqualTo("CAPTURED");
        assertThat(view.get("riskOutcome")).isEqualTo("APPROVE");
        // A read model must not restate authoritative money state.
        assertThat(view).doesNotContainKeys("balanceMinor", "heldMinor", "availableMinor");
    }

    @Test
    void duplicateDeliveryOfTheSameEventProducesOneProjectionEffect() throws Exception {
        UUID payment = authorize(accountId, 3_100);
        deliver(payment);
        Waits.until("first delivery is projected", BUDGET, () -> activityRow(payment) != null);
        int appliedBefore = ((Number) activityRow(payment).get("applied_event_count")).intValue();
        double duplicatesBefore = counter("decisionrail.consumer.duplicates");

        String rawPayload = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1",
                String.class, payment);
        BrokerProbe.publishRaw(TOPIC, payment.toString(), rawPayload);
        BrokerProbe.publishRaw(TOPIC, payment.toString(), rawPayload);

        Waits.until("both duplicates are observed", BUDGET, () -> counter("decisionrail.consumer.duplicates") >= duplicatesBefore + 2);
        assertThat(inbox.consumedCount(GROUP, payment)).isEqualTo(1);
        assertThat(((Number) activityRow(payment).get("applied_event_count")).intValue()).isEqualTo(appliedBefore);
    }

    @Test
    void brokerAcknowledgementThenWorkerCrashResendsWithTheSameIdentity() throws Exception {
        UUID payment = authorize(accountId, 4_200);
        UUID eventId = eventIds(payment).getFirst();

        // The send reaches the broker, then the worker dies before recording the outcome.
        // The fault targets only this payment, so a cycle that also carries unrelated events
        // behaves normally for them. Assertions are about this event, not cycle-wide counts.
        faults.crashAfterAcknowledgementFor(payment);
        Waits.until("the crashing worker claims this payment's event", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "CLAIMED".equals(status(eventId));
        });
        faults.stopCrashingAfterAcknowledgementFor(payment);
        assertThat(status(eventId)).isEqualTo("CLAIMED");
        assertThat(attempts(eventId)).isEqualTo(1);

        // A new worker may only take over once the dead worker's lease has expired.
        assertThat(dispatcher.reclaimExpiredLeases(clock.instant())).isZero();
        int reclaimed = dispatcher.reclaimExpiredLeases(
                clock.instant().plus(properties.dispatcher().leaseDuration()).plusSeconds(1));
        assertThat(reclaimed).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("PENDING");
        // The attempt really happened, so the count is not rewound.
        assertThat(attempts(eventId)).isEqualTo(1);

        deliver(payment);
        assertThat(status(eventId)).isEqualTo("PUBLISHED");

        List<JsonNode> delivered = publishedEnvelopes(payment);
        assertThat(delivered).hasSizeGreaterThanOrEqualTo(2);
        assertThat(delivered).allSatisfy(node -> assertThat(node.path("eventId").asText()).isEqualTo(eventId.toString()));

        // At-least-once on the wire, exactly one effect in the read model.
        Waits.until("projection applied the resent event once", BUDGET, () -> {
            Map<String, Object> activity = activityRow(payment);
            return activity != null && ((Number) activity.get("applied_event_count")).intValue() == 1;
        });
        assertThat(inbox.consumedCount(GROUP, payment)).isEqualTo(1);
    }

    @Test
    void anExpiredWorkerCannotCompleteAnotherWorkersClaim() throws Exception {
        UUID payment = authorize(accountId, 1_700);
        UUID eventId = eventIds(payment).getFirst();
        Instant now = clock.instant();

        List<ClaimedEvent> firstWorker = transactions.execute(status ->
                outbox.claim("worker-a", 10, now, now.plusMillis(1)));
        ClaimedEvent claimed = firstWorker.stream().filter(event -> event.id().equals(eventId)).findFirst().orElseThrow();

        Integer reclaimedCount = transactions.execute(status -> outbox.reclaimExpiredLeases(now.plusSeconds(5)));
        assertThat(reclaimedCount).isGreaterThanOrEqualTo(1);
        List<ClaimedEvent> secondWorker = transactions.execute(status ->
                outbox.claim("worker-b", 10, now.plusSeconds(5), now.plusSeconds(60)));
        ClaimedEvent reclaimed = secondWorker.stream().filter(event -> event.id().equals(eventId)).findFirst().orElseThrow();
        assertThat(reclaimed.leaseToken()).isNotEqualTo(claimed.leaseToken());

        // Every completion path is fenced, so a stale worker cannot publish, retry, or fail it.
        boolean stalePublish = inTransaction(() -> outbox.markPublished(eventId, claimed.leaseToken(), now, 0, 1L));
        boolean staleRetry = inTransaction(() -> outbox.scheduleRetry(eventId, claimed.leaseToken(), now, "stale"));
        boolean staleFail = inTransaction(() -> outbox.markFailed(eventId, claimed.leaseToken(), "stale"));
        assertThat(stalePublish).isFalse();
        assertThat(staleRetry).isFalse();
        assertThat(staleFail).isFalse();
        assertThat(status(eventId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM outbox_events WHERE id = ?", String.class, eventId))
                .isEqualTo("worker-b");

        // The current owner can.
        boolean ownerPublish = inTransaction(() -> outbox.markPublished(eventId, reclaimed.leaseToken(), now, 0, 1L));
        assertThat(ownerPublish).isTrue();
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentWorkersLoseNoEventsAndNeverReorderAPaymentsLifecycle() throws Exception {
        List<UUID> payments = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            UUID payment = authorize(accountId, 1_000 + index);
            capture(payment);
            payments.add(payment);
        }
        List<OutboxDispatcher> workers = List.of(dispatcher, newDispatcher(), newDispatcher());
        assertThat(workers.stream().map(OutboxDispatcher::workerId).distinct()).hasSize(3);

        long deadline = System.nanoTime() + BUDGET.toNanos();
        while (System.nanoTime() < deadline && pendingFor(payments) > 0) {
            runConcurrently(workers.stream().map(worker -> (Callable<OutboxDispatcher.Cycle>) worker::dispatchOnce).toList());
        }
        assertThat(pendingFor(payments)).as("all events delivered").isZero();

        for (UUID payment : payments) {
            List<JsonNode> delivered = publishedEnvelopes(payment);
            List<Long> sequences = delivered.stream().map(node -> node.path("aggregateSequence").asLong()).toList();
            // Duplicates are permitted by an at-least-once guarantee; going backwards is not.
            List<Long> firstOccurrences = new ArrayList<>();
            for (Long sequence : sequences) {
                if (!firstOccurrences.contains(sequence)) firstOccurrences.add(sequence);
            }
            assertThat(firstOccurrences).as("lifecycle order for payment %s", payment).containsExactly(1L, 2L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PUBLISHED'", Long.class, payment))
                    .isEqualTo(2);
        }
    }

    @Test
    void aTerminallyFailedEventBlocksOnlyItsOwnPaymentAndRedriveRestoresDelivery() throws Exception {
        UUID blocked = authorize(accountId, 2_200);
        capture(blocked);
        List<UUID> blockedEvents = eventIds(blocked);

        // Only this payment's sends fail. Other payments keep succeeding in the same cycles,
        // which is both the property worth proving and what keeps the breaker closed so the
        // attempt budget is spent on real broker attempts rather than short-circuited ones.
        faults.rejectSendsFor(blocked);
        Waits.until("the first event exhausts its retry budget", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "FAILED".equals(status(blockedEvents.get(0)));
        });
        faults.allowSendsFor(blocked);
        assertThat(attempts(blockedEvents.get(0))).isEqualTo(properties.dispatcher().maxAttempts());
        // The later event was never offered to the broker: it cannot overtake its predecessor.
        assertThat(status(blockedEvents.get(1))).isEqualTo("PENDING");
        assertThat(attempts(blockedEvents.get(1))).isZero();

        // A different payment keeps draining while that one stream is stalled.
        UUID healthy = authorize(accountId, 2_300);
        Waits.until("an unrelated payment still delivers", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(status(eventIds(healthy).getFirst()));
        });
        assertThat(status(blockedEvents.get(1))).isEqualTo("PENDING");
        assertThat(dispatcher.backlog().blockedPaymentCount()).isGreaterThanOrEqualTo(1);

        // Redriving only the later event cannot jump the queue.
        dispatcher.redrive(null, null, List.of(blockedEvents.get(1)), 10);
        dispatcher.dispatchOnce();
        assertThat(status(blockedEvents.get(1))).isEqualTo("PENDING");
        assertThat(status(blockedEvents.get(0))).isEqualTo("FAILED");

        String redriveBody = json.writeValueAsString(Map.of("paymentId", blocked.toString()));
        String response = mvc.perform(post("/v1/ops/outbox/redrive")
                        .header("Authorization", basic("admin", "admin-test-password-123"))
                        .contentType(MediaType.APPLICATION_JSON).content(redriveBody))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("redrivenCount").asInt()).isEqualTo(1);

        Waits.until("redriven stream drains in order", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(status(blockedEvents.get(0))) && "PUBLISHED".equals(status(blockedEvents.get(1)));
        });
        // Redrive preserved identity rather than minting a replacement event.
        assertThat(eventIds(blocked)).containsExactlyElementsOf(blockedEvents);
        List<Long> order = publishedEnvelopes(blocked).stream().map(node -> node.path("aggregateSequence").asLong()).distinct().toList();
        assertThat(order).containsExactly(1L, 2L);
    }

    @Test
    void malformedUnsupportedConflictingAndUnknownTenantRecordsAreQuarantined() throws Exception {
        UUID payment = authorize(accountId, 2_900);
        deliver(payment);
        Waits.until("baseline event is projected", BUDGET, () -> activityRow(payment) != null);
        long quarantinedBefore = inbox.quarantineCount(GROUP);
        int appliedBefore = ((Number) activityRow(payment).get("applied_event_count")).intValue();
        String valid = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1",
                String.class, payment);

        BrokerProbe.publishRaw(TOPIC, payment.toString(), "{ this is not valid json");
        BrokerProbe.publishRaw(TOPIC, payment.toString(), mutate(valid, node -> node.put("schemaVersion", 99)));
        BrokerProbe.publishRaw(TOPIC, payment.toString(), mutate(valid, node -> node.put("eventType", "payment.refunded.v1")));
        BrokerProbe.publishRaw(TOPIC, payment.toString(), mutate(valid, node -> node.put("merchantId", "ghost-merchant")));
        // Same event id, different content: identity reuse, not a duplicate.
        BrokerProbe.publishRaw(TOPIC, payment.toString(),
                mutate(valid, node -> ((ObjectNode) node.get("payment")).put("amountMinor", 999_999)));

        Waits.until("all five records are quarantined", BUDGET, () -> inbox.quarantineCount(GROUP) >= quarantinedBefore + 5);
        List<String> reasons = jdbc.queryForList(
                "SELECT reason FROM consumer_quarantine WHERE consumer_group = ? ORDER BY quarantined_at DESC LIMIT 5",
                String.class, GROUP);
        assertThat(reasons).contains("MALFORMED", "UNSUPPORTED_SCHEMA", "UNSUPPORTED_TYPE", "UNKNOWN_MERCHANT", "IDENTITY_CONFLICT");
        // None of them changed the read model.
        assertThat(((Number) activityRow(payment).get("applied_event_count")).intValue()).isEqualTo(appliedBefore);
        assertThat(((Number) activityRow(payment).get("amount_minor")).longValue()).isEqualTo(2_900);
    }

    @Test
    void aCommittedConsumerEffectSurvivesRedeliveryAfterALostOffsetAcknowledgement() throws Exception {
        UUID payment = authorize(accountId, 3_600);
        deliver(payment);
        Waits.until("event is projected", BUDGET, () -> activityRow(payment) != null);
        assertThat(((Number) activityRow(payment).get("applied_event_count")).intValue()).isEqualTo(1);
        double duplicatesBefore = counter("decisionrail.consumer.duplicates");

        // Reproduces a consumer that committed its database work and died before the offset
        // commit: the group restarts from an earlier offset and re-reads everything.
        var container = listeners.getListenerContainer("payment-activity-projection");
        container.stop();
        Waits.until("consumer group has no active members", BUDGET, () -> !container.isRunning());
        rewindGroupToStart();
        container.start();

        Waits.until("the redelivered event is recognised as a duplicate", BUDGET,
                () -> counter("decisionrail.consumer.duplicates") > duplicatesBefore);
        assertThat(inbox.consumedCount(GROUP, payment)).isEqualTo(1);
        assertThat(((Number) activityRow(payment).get("applied_event_count")).intValue()).isEqualTo(1);
    }

    // ----- helpers -----

    private void rewindGroupToStart() throws Exception {
        try (AdminClient admin = BrokerProbe.admin()) {
            var description = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(20, TimeUnit.SECONDS).get(TOPIC);
            Map<TopicPartition, OffsetAndMetadata> rewound = new HashMap<>();
            description.partitions().forEach(partition ->
                    rewound.put(new TopicPartition(TOPIC, partition.partition()), new OffsetAndMetadata(0L)));
            admin.alterConsumerGroupOffsets(GROUP, rewound).all().get(30, TimeUnit.SECONDS);
        }
    }

    private boolean inTransaction(java.util.function.BooleanSupplier work) {
        Boolean result = transactions.execute(status -> work.getAsBoolean());
        return Boolean.TRUE.equals(result);
    }

    private OutboxDispatcher newDispatcher() {
        return new OutboxDispatcher(outbox, publisher, properties, faults, brokerBreaker, transactions, clock, metrics);
    }

    /**
     * Drives cycles until every event for these payments is published. Scoped per payment on
     * purpose: this suite shares one database with the other integration tests, so a global
     * "backlog is empty" condition would be asserting something this test does not control.
     */
    private void deliver(UUID... payments) {
        List<UUID> targets = List.of(payments);
        Waits.until("events for " + targets + " are published", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return pendingFor(targets) == 0;
        });
    }

    private long pendingFor(List<UUID> payments) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ANY (?::uuid[]) AND status <> 'PUBLISHED'
                """, Long.class, "{" + String.join(",", payments.stream().map(UUID::toString).toList()) + "}");
    }

    private String mutate(String payload, java.util.function.Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = (ObjectNode) json.readTree(payload);
        change.accept(node);
        return json.writeValueAsString(node);
    }

    private List<JsonNode> publishedEnvelopes(UUID payment) {
        List<JsonNode> matching = new ArrayList<>();
        for (ConsumerRecord<String, String> record : BrokerProbe.drain(TOPIC, Duration.ofSeconds(3))) {
            try {
                JsonNode node = json.readTree(record.value());
                if (payment.toString().equals(node.path("aggregateId").asText())) matching.add(node);
            } catch (Exception ignored) {
                // Raw malformed records injected by other tests are not envelopes.
            }
        }
        return matching;
    }

    private List<Long> outboxSequences(UUID payment) {
        return jdbc.queryForList("SELECT aggregate_sequence FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence",
                Long.class, payment);
    }

    private List<UUID> eventIds(UUID payment) {
        return jdbc.queryForList("SELECT id FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence",
                UUID.class, payment);
    }

    private List<Map<String, Object>> outboxRows(UUID payment) {
        return jdbc.queryForList("SELECT * FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence", payment);
    }

    private Map<String, Object> activityRow(UUID payment) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM payment_activity WHERE payment_id = ?", payment);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private int attempts(UUID eventId) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, eventId);
    }

    private double counter(String name) {
        return metrics.find(name).counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private UUID newAccount(String merchant, long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                id, merchant, balance, balance);
        return id;
    }

    private UUID authorize(UUID account, long amount) throws Exception {
        String body = json.writeValueAsString(Map.of("accountId", account, "amountMinor", amount, "currency", "CAD", "country", "CA"));
        String response = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        assertThat(node.path("status").asText()).isEqualTo("AUTHORIZED");
        return UUID.fromString(node.path("id").asText());
    }

    private void capture(UUID payment) throws Exception {
        int status = mvc.perform(post("/v1/payments/" + payment + "/capture").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
    }

    private Map<String, Object> readJson(String path) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", DEMO)).andReturn().getResponse().getContentAsString();
        return json.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private <T> List<T> runConcurrently(List<Callable<T>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        }
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
