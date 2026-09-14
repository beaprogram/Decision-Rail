package com.decisionrail.ui;

import com.decisionrail.payments.AuthorizationCommand;
import com.decisionrail.payments.PaymentService;
import com.decisionrail.payments.PaymentView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The read APIs the dashboard needs: account listing, authoritative payment search, and the lifecycle
 * timeline. Tenant isolation is asserted on every one of them.
 *
 * <p>Payments are created through {@link PaymentService}, the same transactional path the API uses, so
 * what is searched is real committed state rather than rows inserted behind the service's back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardReadApiIntegrationTest {

    @DynamicPropertySource
    static void noListeners(DynamicPropertyRegistry registry) {
        // Search must work with nothing delivered, which is easiest to guarantee by never delivering.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;

    private String merchant;
    private String otherMerchant;
    private UUID demoAccount;
    private UUID demoSecondAccount;
    private UUID otherAccount;

    @BeforeEach
    void accountsForTwoMerchants() {
        // Two merchants of this test's own. The suite shares one database, so assertions about what a
        // search returns are only meaningful against a tenant no other test writes to.
        merchant = newMerchant("ui-a");
        otherMerchant = newMerchant("ui-b");
        demoAccount = newAccount(merchant, "CAD", 5_000_000);
        demoSecondAccount = newAccount(merchant, "USD", 4_000_000);
        otherAccount = newAccount(otherMerchant, "CAD", 5_000_000);
    }

    private String newMerchant(String prefix) {
        String id = prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO merchants (id) VALUES (?)", id);
        return id;
    }

    @Test
    void aPaymentIsSearchableImmediatelyAfterItsTransactionCommitsWithNothingDelivered() throws Exception {
        PaymentView created = authorize(merchant, demoAccount, 2_500, "CAD", "CA");

        // Nothing has been published or projected: search must not depend on the asynchronous path.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PUBLISHED'",
                Long.class, created.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_activity WHERE payment_id = ?",
                Long.class, created.id())).isZero();

        JsonNode page = read("/ui/payments?paymentId=" + created.id(), merchant, "MERCHANT");
        assertThat(page.path("payments")).hasSize(1);
        JsonNode row = page.path("payments").get(0);
        assertThat(row.path("id").asText()).isEqualTo(created.id().toString());
        assertThat(row.path("amountMinor").asLong()).isEqualTo(2_500);
        assertThat(row.path("currency").asText()).isEqualTo("CAD");
        assertThat(row.path("status").asText()).isEqualTo("AUTHORIZED");
        // The stored risk decision travels with the summary, and is distinct from the status.
        assertThat(row.path("riskOutcome").asText()).isEqualTo("APPROVE");
        assertThat(row.path("policyVersion").asText()).isEqualTo("demo-v1");
    }

    @Test
    void filtersNarrowByStatusRiskOutcomeCurrencyAccountAndCreationTime() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(1);
        PaymentView approved = authorize(merchant, demoAccount, 2_500, "CAD", "CA");
        PaymentView review = authorize(merchant, demoAccount, 150_000, "CAD", "CA");
        PaymentView declined = authorize(merchant, demoAccount, 600_000, "CAD", "CA");
        PaymentView usd = authorize(merchant, demoSecondAccount, 3_100, "USD", "US");
        Instant after = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(1);

        assertThat(review.status().name()).isEqualTo("REVIEW");
        assertThat(declined.status().name()).isEqualTo("DECLINED");

        assertThat(ids(search("status=REVIEW&createdFrom=" + before))).containsExactly(review.id());
        assertThat(ids(search("status=AUTHORIZED&createdFrom=" + before)))
                .contains(approved.id(), usd.id()).doesNotContain(review.id(), declined.id());
        assertThat(ids(search("riskOutcome=DECLINE&createdFrom=" + before))).containsExactly(declined.id());
        assertThat(ids(search("currency=USD&createdFrom=" + before))).containsExactly(usd.id());
        assertThat(ids(search("accountId=" + demoSecondAccount))).containsExactly(usd.id());
        // Several values for one filter are allowed.
        assertThat(ids(search("status=REVIEW,DECLINED&createdFrom=" + before)))
                .containsExactlyInAnyOrder(review.id(), declined.id());
        // A window that ends before these payments existed returns none of them.
        assertThat(ids(search("createdTo=" + before))).doesNotContain(approved.id(), review.id(), declined.id(), usd.id());
        assertThat(ids(search("createdFrom=" + before + "&createdTo=" + after)))
                .contains(approved.id(), review.id(), declined.id(), usd.id());
    }

    @Test
    void pagingIsDeterministicAndNeverRepeatsOrSkipsARowWhenNewPaymentsArrive() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(1);
        List<UUID> created = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            created.add(authorize(merchant, demoAccount, 1_100 + index, "CAD", "CA").id());
        }

        JsonNode first = search("limit=3&createdFrom=" + before);
        assertThat(first.path("payments")).hasSize(3);
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(first.path("matchedCount").asLong()).isEqualTo(7);
        assertThat(first.path("matchedCountCapped").asBoolean()).isFalse();

        // A payment created between pages must not shift the boundary and hide a row.
        UUID late = authorize(merchant, demoAccount, 9_900, "CAD", "CA").id();

        List<UUID> paged = new ArrayList<>(ids(first));
        String cursor = first.path("nextCursor").asText();
        while (cursor != null && !cursor.isBlank()) {
            JsonNode next = search("limit=3&createdFrom=" + before + "&cursor=" + cursor);
            paged.addAll(ids(next));
            cursor = next.path("nextCursor").isNull() ? null : next.path("nextCursor").asText();
        }

        // Every originally created payment appears exactly once, and the later one never appears,
        // because it sorts ahead of the first page's cursor.
        assertThat(paged).doesNotHaveDuplicates().containsAll(created).doesNotContain(late);

        // Newest first, strictly ordered.
        List<UUID> expectedOrder = new ArrayList<>(created);
        java.util.Collections.reverse(expectedOrder);
        assertThat(paged.subList(0, created.size())).isEqualTo(expectedOrder);
    }

    @Test
    void invalidFiltersAndCursorsAreRejectedRatherThanIgnored() throws Exception {
        assertThat(status("/ui/payments?status=NOT_A_STATUS", merchant, "MERCHANT")).isEqualTo(400);
        assertThat(status("/ui/payments?riskOutcome=MAYBE", merchant, "MERCHANT")).isEqualTo(400);
        assertThat(status("/ui/payments?currency=EUR", merchant, "MERCHANT")).isEqualTo(400);
        assertThat(status("/ui/payments?limit=0", merchant, "MERCHANT")).isEqualTo(400);
        assertThat(status("/ui/payments?limit=500", merchant, "MERCHANT")).isEqualTo(400);
        assertThat(status("/ui/payments?paymentId=not-a-uuid", merchant, "MERCHANT")).isEqualTo(400);
        assertThat(status("/ui/payments?createdFrom=yesterday", merchant, "MERCHANT")).isEqualTo(400);
        assertThat(status("/ui/payments?createdFrom=2026-09-02T00:00:00Z&createdTo=2026-09-01T00:00:00Z",
                merchant, "MERCHANT")).isEqualTo(400);
        // A corrupted cursor must not silently restart paging and hand back rows already seen.
        assertThat(status("/ui/payments?cursor=not-a-cursor", merchant, "MERCHANT")).isEqualTo(400);
    }

    @Test
    void oneMerchantCannotSeeAnotherMerchantsPaymentsThroughAnyReadRoute() throws Exception {
        PaymentView mine = authorize(merchant, demoAccount, 2_500, "CAD", "CA");
        PaymentView theirs = authorize(otherMerchant, otherAccount, 2_500, "CAD", "CA");

        // Search is scoped by the authenticated merchant, not by a request parameter.
        assertThat(ids(search("limit=200"))).contains(mine.id()).doesNotContain(theirs.id());
        assertThat(ids(searchAs(otherMerchant, "limit=200"))).contains(theirs.id()).doesNotContain(mine.id());
        // Naming the other tenant's payment explicitly does not reveal it.
        assertThat(search("paymentId=" + theirs.id()).path("payments")).isEmpty();

        // Detail, ledger, timeline and shadow all refuse it, and as "not found" rather than
        // "forbidden", so identifiers cannot be probed for existence.
        for (String path : List.of("/ui/payments/" + theirs.id(), "/ui/payments/" + theirs.id() + "/ledger",
                "/ui/payments/" + theirs.id() + "/timeline")) {
            assertThat(status(path, merchant, "MERCHANT")).as(path).isEqualTo(404);
        }
        assertThat(read("/ui/payments/" + theirs.id() + "/shadow", merchant, "MERCHANT")).isEmpty();

        // Account listing and account detail are scoped the same way.
        assertThat(accountIds(read("/ui/accounts", merchant, "MERCHANT")))
                .contains(demoAccount).doesNotContain(otherAccount);
        assertThat(status("/ui/accounts/" + otherAccount, merchant, "MERCHANT")).isEqualTo(404);
    }

    @Test
    void theAccountListCarriesBalanceHoldAndAvailableWithItsOwnCurrency() throws Exception {
        authorize(merchant, demoAccount, 2_500, "CAD", "CA");

        JsonNode accounts = read("/ui/accounts", merchant, "MERCHANT");
        JsonNode cad = accountFor(accounts, demoAccount);
        JsonNode usd = accountFor(accounts, demoSecondAccount);

        // Each account reports its own currency; nothing is summed across currencies.
        assertThat(cad.path("currency").asText()).isEqualTo("CAD");
        assertThat(usd.path("currency").asText()).isEqualTo("USD");
        assertThat(cad.path("heldMinor").asLong()).isEqualTo(2_500);
        assertThat(cad.path("availableMinor").asLong())
                .isEqualTo(cad.path("balanceMinor").asLong() - cad.path("heldMinor").asLong());
        assertThat(usd.path("heldMinor").asLong()).isZero();
    }

    @Test
    void anInsufficientFundsDeclineIsReportedAsAnApproveRiskDecision() throws Exception {
        UUID tight = newAccount(merchant, "CAD", 1_500);
        authorize(merchant, tight, 1_200, "CAD", "CA");
        PaymentView fundsDeclined = authorize(merchant, tight, 1_100, "CAD", "CA");

        JsonNode row = search("paymentId=" + fundsDeclined.id()).path("payments").get(0);
        assertThat(row.path("status").asText()).isEqualTo("DECLINED");
        // The decline was about funds, not policy, and the summary keeps the two separable.
        assertThat(row.path("riskOutcome").asText()).isEqualTo("APPROVE");
        assertThat(row.path("failureCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void theTimelineSeparatesTheCommandFromPublicationAndFromConsumption() throws Exception {
        PaymentView payment = authorize(merchant, demoAccount, 2_500, "CAD", "CA");

        JsonNode timeline = read("/ui/payments/" + payment.id() + "/timeline", merchant, "MERCHANT");
        assertThat(timeline.path("paymentId").asText()).isEqualTo(payment.id().toString());
        assertThat(timeline.path("status").asText()).isEqualTo("AUTHORIZED");

        // The command is durable evidence from inside the payment transaction.
        assertThat(timeline.path("commands")).hasSize(1);
        assertThat(timeline.path("commands").get(0).path("action").asText()).isEqualTo("payment.authorized.v1");
        assertThat(timeline.path("commands").get(0).path("occurredAt").asText()).isNotBlank();

        // The event exists and is committed, but nothing has been published or consumed, and the
        // response says so rather than inventing a publication time.
        assertThat(timeline.path("events")).hasSize(1);
        JsonNode event = timeline.path("events").get(0);
        assertThat(event.path("sequence").asLong()).isEqualTo(1);
        assertThat(event.path("eventType").asText()).isEqualTo("payment.authorized.v1");
        assertThat(event.path("committedAt").asText()).isNotBlank();
        assertThat(event.path("deliveryStatus").asText()).isEqualTo("PENDING");
        assertThat(event.path("publishedAt").isNull()).isTrue();
        assertThat(event.path("brokerPartition").isNull()).isTrue();
        assertThat(event.path("consumers")).isEmpty();
        // A merchant response carries no internal diagnostic text.
        assertThat(event.path("lastFailureKind").isNull()).isTrue();

        // No consumer has projected it, so there is no projection state at all.
        assertThat(timeline.path("projection").isNull()).isTrue();
    }

    @Test
    void theTimelineOrdersSeveralLifecycleEventsByTheirDurableSequence() throws Exception {
        PaymentView authorized = authorize(merchant, demoAccount, 2_500, "CAD", "CA");
        payments.capture(merchant, "ui-capture-" + UUID.randomUUID(), authorized.id());

        JsonNode timeline = read("/ui/payments/" + authorized.id() + "/timeline", merchant, "MERCHANT");
        assertThat(timeline.path("status").asText()).isEqualTo("CAPTURED");
        assertThat(timeline.path("commands")).hasSize(2);
        assertThat(timeline.path("events")).hasSize(2);
        assertThat(timeline.path("events").get(0).path("sequence").asLong()).isEqualTo(1);
        assertThat(timeline.path("events").get(0).path("eventType").asText()).isEqualTo("payment.authorized.v1");
        assertThat(timeline.path("events").get(1).path("sequence").asLong()).isEqualTo(2);
        assertThat(timeline.path("events").get(1).path("eventType").asText()).isEqualTo("payment.captured.v1");
    }

    @Test
    void theFailedEventListIsAdministrativeAndMarksStalledPaymentStreams() throws Exception {
        PaymentView payment = authorize(merchant, demoAccount, 2_500, "CAD", "CA");
        payments.capture(merchant, "ui-capture-" + UUID.randomUUID(), payment.id());
        // Fail the first event so the second is queued behind it: a stalled stream.
        jdbc.update("""
                UPDATE outbox_events SET status = 'FAILED', attempts = 8,
                    last_error = 'BrokerSendException: simulated terminal failure',
                    last_attempt_at = now()
                WHERE aggregate_id = ? AND aggregate_sequence = 1
                """, payment.id());

        JsonNode page = read("/ui/ops/outbox/failed?limit=50", "admin", "ADMIN");
        JsonNode entry = failedEntryFor(page.path("events"), payment.id());
        assertThat(entry.path("aggregateSequence").asLong()).isEqualTo(1);
        assertThat(entry.path("eventType").asText()).isEqualTo("payment.authorized.v1");
        assertThat(entry.path("attempts").asInt()).isEqualTo(8);
        assertThat(entry.path("merchantId").asText()).isEqualTo(merchant);
        // Administrators get the full stored detail, which is what makes a redrive decision possible.
        assertThat(entry.path("lastError").asText()).contains("simulated terminal failure");
        // And the marker that says this failure is holding a whole payment's stream.
        assertThat(entry.path("blocksLaterEvents").asBoolean()).isTrue();
        assertThat(page.path("totalFailed").asLong()).isGreaterThanOrEqualTo(1);

        // Merchants cannot reach it at all.
        assertThat(status("/ui/ops/outbox/failed", merchant, "MERCHANT")).isEqualTo(403);
    }

    @Test
    void theDeliverySummaryReportsLivenessReadinessAndAsynchronousCapabilitySeparately() throws Exception {
        JsonNode delivery = read("/ui/ops/delivery", "admin", "ADMIN");

        assertThat(delivery.path("liveness").asText()).isEqualTo("UP");
        assertThat(delivery.path("readiness").asText()).isEqualTo("UP");
        // Three distinct signals, not one rolled-up status.
        assertThat(delivery.path("asyncDelivery").asText()).isIn("UP", "DEGRADED");
        assertThat(delivery.path("breakerState").asText()).isIn("CLOSED", "HALF_OPEN", "OPEN");
        assertThat(delivery.path("countsByStatus").isObject()).isTrue();
        assertThat(delivery.path("blockedPaymentCount").isNumber()).isTrue();
    }

    @Test
    void browserRedriveRefusesARequestWithNoTargetAtAll() throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ui/ops/outbox/redrive")
                        .with(user("admin").roles("ADMIN"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        // An empty filter set in a browser is a mistake, not an instruction to redrive everything.
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("REDRIVE_TARGET_REQUIRED");
    }

    // ----- helpers -----

    private PaymentView authorize(String merchant, UUID account, long amountMinor, String currency, String country) {
        return payments.authorize(merchant, "ui-test-" + UUID.randomUUID(),
                new AuthorizationCommand(account, amountMinor, currency, country)).body();
    }

    private UUID newAccount(String merchant, String currency, long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,?,?,?)",
                id, merchant, currency, balance, balance);
        return id;
    }

    private JsonNode search(String query) throws Exception {
        return searchAs(merchant, query);
    }

    private JsonNode searchAs(String merchant, String query) throws Exception {
        return read("/ui/payments?" + query, merchant, "MERCHANT");
    }

    private List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("payments").forEach(row -> ids.add(UUID.fromString(row.path("id").asText())));
        return ids;
    }

    private List<UUID> accountIds(JsonNode accounts) {
        List<UUID> ids = new ArrayList<>();
        accounts.forEach(row -> ids.add(UUID.fromString(row.path("id").asText())));
        return ids;
    }

    private JsonNode accountFor(JsonNode accounts, UUID id) {
        for (JsonNode account : accounts) {
            if (id.toString().equals(account.path("id").asText())) return account;
        }
        throw new AssertionError("account " + id + " is not in the listing");
    }

    private JsonNode failedEntryFor(JsonNode events, UUID paymentId) {
        for (JsonNode event : events) {
            if (paymentId.toString().equals(event.path("paymentId").asText())) return event;
        }
        throw new AssertionError("no failed event for payment " + paymentId);
    }

    private JsonNode read(String path, String username, String role) throws Exception {
        MvcResult result = perform(get(path), username, role);
        assertThat(result.getResponse().getStatus()).as("GET %s as %s", path, username).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int status(String path, String username, String role) throws Exception {
        return perform(get(path), username, role).getResponse().getStatus();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String username, String role) throws Exception {
        return mvc.perform(request.with(user(username).roles(role))).andReturn();
    }
}
