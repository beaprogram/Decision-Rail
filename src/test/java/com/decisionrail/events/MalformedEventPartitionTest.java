package com.decisionrail.events;

import com.decisionrail.policy.PolicyService;
import com.decisionrail.shadow.ShadowService;
import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A record the consumer must refuse cannot stall the records behind it.
 *
 * <p>Every refused record here is published to one explicit partition with a valid record queued
 * after it, so processing the second one proves the first did not block the partition. Before the
 * contract was aligned with the consumer tables, a currency of "cad" passed validation and then
 * failed a column CHECK on insert; that arrived as a storage error, which the retry policy treats as
 * a transient outage and retries indefinitely.
 *
 * <p>The last check goes the other way: a genuine storage failure, including a failure to persist the
 * quarantine record itself, must not advance the offset, because acknowledging uncommitted work would
 * turn a recoverable outage into lost events.
 */
@SpringBootTest
@ActiveProfiles("test")
class MalformedEventPartitionTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.malformed." + RUN;
    private static final String PROJECTION_GROUP = "test-malformed-projection-" + RUN;
    private static final String SHADOW_GROUP = "test-malformed-shadow-" + RUN;
    private static final int PARTITION = 0;
    private static final Duration BUDGET = Duration.ofSeconds(40);

    private static final String STRICT_CANDIDATE = """
            {"rules":[
              {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
               "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
               "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
            ]}""";

    @DynamicPropertySource
    static void topics(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        BrokerProbe.ensureTopic(TOPIC, 3);
        registry.add("spring.kafka.bootstrap-servers", BrokerProbe::bootstrapServers);
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> PROJECTION_GROUP);
        registry.add("app.events.shadow-group", () -> SHADOW_GROUP);
    }

    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired InboxStore inbox;
    @Autowired ShadowService shadowService;
    @Autowired PolicyService policies;

    private String candidateVersion;

    @BeforeEach
    void enableShadowSoBothConsumersSeeEveryRecord() throws Exception {
        candidateVersion = "malformed-candidate-" + UUID.randomUUID().toString().substring(0, 8);
        policies.createCandidate(candidateVersion, json.readTree(STRICT_CANDIDATE), "admin");
        shadowService.configure(true, candidateVersion, "admin");
    }

    @AfterEach
    void disableShadow() {
        shadowService.configure(false, null, "admin");
    }

    @Test
    void aNonCanonicalCurrencyIsQuarantinedAndTheNextRecordStillFlows() throws Exception {
        long quarantinedBefore = inbox.quarantineCount(PROJECTION_GROUP);
        UUID refused = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();

        // The value that used to pass validation and then fail a column CHECK on insert.
        publish(refused, node -> ((ObjectNode) node.get("payment")).put("currency", "cad"));
        publish(accepted, node -> { });

        // The record queued behind it is processed, so the partition was never blocked.
        Waits.until("the valid record behind it is projected", BUDGET, () -> projectionExists(accepted));
        Waits.until("the refused record is quarantined", BUDGET,
                () -> inbox.quarantineCount(PROJECTION_GROUP) > quarantinedBefore);

        assertThat(quarantineReasons(PROJECTION_GROUP)).contains("MALFORMED");
        // No projection effect and no shadow work from the refused record.
        assertThat(projectionExists(refused)).isFalse();
        assertThat(shadowTaskCount(refused)).isZero();
        assertThat(consumedCount(PROJECTION_GROUP, refused)).isZero();
        // Both consumers refused it, using the same shared contract.
        Waits.until("the shadow consumer also quarantined it", BUDGET,
                () -> inbox.quarantineCount(SHADOW_GROUP) > 0);
        assertThat(quarantineReasons(SHADOW_GROUP)).contains("MALFORMED");
        // And the accepted record did produce shadow work, so the shadow consumer was not stalled.
        Waits.until("the valid record produced shadow work", BUDGET, () -> shadowTaskCount(accepted) == 1);
    }

    @Test
    void otherStorageBoundViolationsAreAlsoQuarantinedWithoutBlocking() throws Exception {
        UUID longStatus = UUID.randomUUID();
        UUID longPolicyVersion = UUID.randomUUID();
        UUID longFailureCode = UUID.randomUUID();
        UUID lowercaseCountry = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();

        publish(longStatus, node -> ((ObjectNode) node.get("payment")).put("status", "S".repeat(40)));
        publish(longPolicyVersion, node ->
                ((ObjectNode) node.get("payment").get("decision")).put("ruleSetVersion", "v".repeat(80)));
        publish(longFailureCode, node -> ((ObjectNode) node.get("payment")).put("failureCode", "F".repeat(80)));
        publish(lowercaseCountry, node -> ((ObjectNode) node.get("payment")).put("country", "ca"));
        publish(accepted, node -> { });

        // The valid record at the back of the queue still arrives.
        Waits.until("the valid record behind four refusals is projected", BUDGET, () -> projectionExists(accepted));

        for (UUID refused : List.of(longStatus, longPolicyVersion, longFailureCode, lowercaseCountry)) {
            assertThat(projectionExists(refused)).as("refused payment %s must not be projected", refused).isFalse();
            assertThat(shadowTaskCount(refused)).as("refused payment %s must produce no shadow work", refused).isZero();
        }
        assertThat(quarantineReasons(PROJECTION_GROUP)).contains("MALFORMED");
        assertThat(quarantineCountFor(PROJECTION_GROUP)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void aFailureToPersistTheQuarantineRecordDoesNotAdvanceTheOffset() throws Exception {
        UUID refused = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();

        // Drain first so the committed offset reflects a known, settled position.
        publish(accepted, node -> { });
        Waits.until("the first valid record is projected", BUDGET, () -> projectionExists(accepted));
        Waits.until("its offset is committed", BUDGET,
                () -> BrokerProbe.committedOffset(PROJECTION_GROUP, TOPIC, PARTITION) > 0);
        long settledOffset = BrokerProbe.committedOffset(PROJECTION_GROUP, TOPIC, PARTITION);
        // Sibling tests in this class quarantine records of their own, so this compares against a
        // baseline captured here rather than against a class-wide total. Assertions that assume an
        // empty table make a test depend on the order its siblings happened to run in.
        long quarantinedBeforeFault = quarantineCountFor(PROJECTION_GROUP);

        String trigger = "test_block_quarantine_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'injected quarantine storage failure' USING ERRCODE = '40001'; END $$");
        try {
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON consumer_quarantine "
                    + "FOR EACH ROW EXECUTE FUNCTION " + trigger + "()");
            publish(refused, node -> ((ObjectNode) node.get("payment")).put("currency", "cad"));

            // The consumer cannot record the refusal, so it must not acknowledge the record either.
            // Holding the partition is the correct outcome: skipping would lose the event silently.
            Waits.neverDuring("the offset advances while quarantine cannot be persisted", Duration.ofSeconds(8),
                    () -> BrokerProbe.committedOffset(PROJECTION_GROUP, TOPIC, PARTITION) > settledOffset);
            assertThat(quarantineCountFor(PROJECTION_GROUP))
                    .as("no new quarantine row can exist while the insert is failing")
                    .isEqualTo(quarantinedBeforeFault);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON consumer_quarantine");
            jdbc.execute("DROP FUNCTION IF EXISTS " + trigger + "()");
        }

        // Once storage recovers, the same record is quarantined and progress resumes. Nothing was lost.
        Waits.until("the refusal is recorded after storage recovers", BUDGET,
                () -> quarantineCountFor(PROJECTION_GROUP) > quarantinedBeforeFault);
        Waits.until("the offset advances after storage recovers", BUDGET,
                () -> BrokerProbe.committedOffset(PROJECTION_GROUP, TOPIC, PARTITION) > settledOffset);
        assertThat(projectionExists(refused)).isFalse();

        UUID after = UUID.randomUUID();
        publish(after, node -> { });
        Waits.until("a later record is processed normally", BUDGET, () -> projectionExists(after));
    }

    // ----- helpers -----

    /** Publishes one envelope to the fixed partition, after applying a mutation to it. */
    private void publish(UUID paymentId, Consumer<JsonNode> mutation) throws Exception {
        ObjectNode envelope = (ObjectNode) json.readTree(canonicalEnvelope(paymentId));
        mutation.accept(envelope);
        BrokerProbe.publishRawToPartition(TOPIC, PARTITION, paymentId.toString(), json.writeValueAsString(envelope));
    }

    private String canonicalEnvelope(UUID paymentId) {
        return """
                {"eventId":"%s","eventType":"payment.authorized.v1","schemaVersion":1,
                 "aggregateId":"%s","aggregateType":"payment","aggregateSequence":1,
                 "merchantId":"demo-merchant","occurredAt":"2026-09-10T12:00:00Z","recordedAt":"2026-09-10T12:00:00Z",
                 "payment":{"id":"%s","accountId":"%s","amountMinor":2500,"currency":"CAD","country":"CA",
                   "status":"AUTHORIZED","failureCode":null,
                   "createdAt":"2026-09-10T12:00:00Z","updatedAt":"2026-09-10T12:00:00Z",
                   "decision":{"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1",
                     "reasons":[{"code":"NO_RISK_SIGNALS","description":"No synthetic demo risk rules matched.","scoreContribution":0}],
                     "flags":[]}}}
                """.formatted(UUID.randomUUID(), paymentId, paymentId, UUID.randomUUID());
    }

    private boolean projectionExists(UUID paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_activity WHERE payment_id = ?", Long.class, paymentId) > 0;
    }

    private long shadowTaskCount(UUID paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM shadow_tasks WHERE payment_id = ?", Long.class, paymentId);
    }

    private long consumedCount(String group, UUID aggregateId) {
        return inbox.consumedCount(group, aggregateId);
    }

    private long quarantineCountFor(String group) {
        return inbox.quarantineCount(group);
    }

    private List<String> quarantineReasons(String group) {
        return jdbc.queryForList("SELECT reason FROM consumer_quarantine WHERE consumer_group = ?", String.class, group);
    }
}
