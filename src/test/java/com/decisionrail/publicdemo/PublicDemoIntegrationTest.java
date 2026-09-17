package com.decisionrail.publicdemo;

import com.decisionrail.reconciliation.ReconciliationService;
import com.decisionrail.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The public portfolio instance: one more identity, and the budgets that keep a shared, reachable
 * instance bounded.
 *
 * <p>Everything here is asserted through both API chains where both exist, because the stateless
 * {@code /v1} API is the obvious way to try to get around a restriction the dashboard shows. A limit
 * that only the browser API enforced would not be a limit.
 *
 * <p>The budgets are set very low for the test so they can be reached in a handful of requests. What
 * is under test is that they are enforced, from where, and what a refusal leaves behind; the numbers
 * the deployment uses are configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.public-demo.enabled=true",
        "app.public-demo.visitor-password=visitor-test-password-1234",
        "app.public-demo.commands-per-minute=4",
        "app.public-demo.replay-jobs-per-hour=2",
        "app.public-demo.max-running-replay-jobs=1",
        "app.public-demo.reconciliation-per-minute=2",
        "app.public-demo.max-payments-per-account=2",
        "app.public-demo.auth-failures-per-window=3",
        "app.ui.secure-cookies=true",
        "app.events.fault-injection-enabled=false",
        "app.build.commit=test-commit-sha",
        "app.build.image=ghcr.io/example/decisionrail:test"
})
class PublicDemoIntegrationTest {
    private static final String VISITOR = basic("visitor", "visitor-test-password-1234");
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String OPERATIONS = basic("operations", "operations-test-password-123");
    private static final String ADMIN = basic("admin", "admin-test-password-123");
    private static final String CANDIDATE = """
            {"rules":[
              {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
               "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
               "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
            ]}""";

    /**
     * The budgets are windows over the application clock, and the clock is injected, so each test
     * starts two hours later than the last one: every window is empty again, and nothing here has to
     * sleep or depend on which test ran first.
     */
    @TestConfiguration
    static class AdvanceableTime {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicDemoProperties properties;
    @Autowired MutableClock clock;
    @MockitoSpyBean ReconciliationService reconciliation;

    private UUID visitorAccount;
    private UUID privateAccount;

    @BeforeEach
    void isolatedAccountsAndAFreshWindow() {
        clock.advance(Duration.ofHours(2));
        visitorAccount = newAccount("visitor");
        privateAccount = newAccount("demo-merchant");
    }

    // ----- the identity -----

    @Test
    void theVisitorIsAnOrdinaryMerchantToEveryOwnershipRule() throws Exception {
        // Its own payment, made through the stateless API with the real credential.
        UUID mine = authorize(VISITOR, visitorAccount, 500, 201);
        assertThat(status(get("/v1/payments/" + mine).header("Authorization", VISITOR))).isEqualTo(200);

        // Another merchant's payment is absent, not forbidden: ids cannot be probed.
        UUID theirs = authorize(DEMO, privateAccount, 500, 201);
        assertThat(status(get("/v1/payments/" + theirs).header("Authorization", VISITOR))).isEqualTo(404);
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", VISITOR))).isEqualTo(404);
        // And the other way round.
        assertThat(status(get("/v1/payments/" + mine).header("Authorization", DEMO))).isEqualTo(404);
    }

    @Test
    void theVisitorHasNoAdministrativeReachOnEitherChain() throws Exception {
        assertThat(status(post("/v1/policies").header("Authorization", VISITOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionId\":\"visitor-attempt\",\"definition\":" + CANDIDATE + "}"))).isEqualTo(403);
        assertThat(status(get("/v1/ops/outbox/backlog").header("Authorization", VISITOR))).isEqualTo(403);
        assertThat(status(put("/v1/ops/shadow").header("Authorization", VISITOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))).isEqualTo(403);
        assertThat(status(post("/v1/ops/outbox/redrive").header("Authorization", VISITOR)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))).isEqualTo(403);
        assertThat(status(get("/actuator/prometheus").header("Authorization", VISITOR))).isEqualTo(403);

        MockHttpSession browser = signIn("visitor", "visitor-test-password-1234");
        assertThat(status(get("/ui/ops/delivery").session(browser))).isEqualTo(403);
        assertThat(status(post("/ui/policies").session(browser).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionId\":\"visitor-attempt-ui\",\"definition\":" + CANDIDATE + "}"))).isEqualTo(403);
        assertThat(status(put("/ui/ops/shadow").session(browser).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))).isEqualTo(403);
        // Capabilities say the same thing, but they are presentation; the refusals above are the rule.
        JsonNode identity = json.readTree(mvc.perform(get("/ui/identity").session(browser)).andReturn()
                .getResponse().getContentAsString());
        assertThat(identity.path("capabilities").path("registerPolicies").asBoolean()).isFalse();
        assertThat(identity.path("capabilities").path("administerDelivery").asBoolean()).isFalse();
    }

    @Test
    void theBootstrapIntroducesTheDemoAndNothingPrivate() throws Exception {
        String body = mvc.perform(get("/ui/identity")).andReturn().getResponse().getContentAsString();
        JsonNode demo = json.readTree(body).path("publicDemo");
        assertThat(demo.path("visitorUsername").asText()).isEqualTo("visitor");
        assertThat(demo.path("visitorPassword").asText()).isEqualTo("visitor-test-password-1234");
        assertThat(demo.path("sharedState").asBoolean()).isTrue();
        assertThat(demo.path("maxPaymentsPerAccount").asInt()).isEqualTo(2);
        // No private credential travels with it.
        assertThat(body).doesNotContain("demo-test-password", "admin-test-password", "operations-test-password");
    }

    // ----- the budgets -----

    @Test
    void theCommandBudgetRefusesBeforeAnythingIsWrittenAndOnBothChains() throws Exception {
        // Four commands a minute. The private merchant is not budgeted at all.
        for (int i = 0; i < 6; i++) authorize(DEMO, privateAccount, 100, 201);

        MockHttpSession browser = signIn("visitor", "visitor-test-password-1234");
        // Two through the browser API, two through the stateless one: one budget. Spread over two
        // accounts so the per-account history cap, tested separately, is not what answers here.
        UUID otherVisitorAccount = newAccount("visitor");
        authorizeThroughBrowser(browser, visitorAccount, 100, 201);
        authorizeThroughBrowser(browser, visitorAccount, 100, 201);
        authorize(VISITOR, otherVisitorAccount, 100, 201);
        authorize(VISITOR, otherVisitorAccount, 100, 201);

        UUID thirdVisitorAccount = newAccount("visitor");
        long before = count("SELECT count(*) FROM payments WHERE account_id = ?", thirdVisitorAccount);
        long keysBefore = count("SELECT count(*) FROM idempotency_records WHERE merchant_id = ?", "visitor");
        MvcResult refused = authorizeRaw(VISITOR, thirdVisitorAccount, 100, UUID.randomUUID().toString());
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        JsonNode problem = json.readTree(refused.getResponse().getContentAsString());
        assertThat(problem.path("code").asText()).isEqualTo("DEMO_CAPACITY_EXHAUSTED");
        assertThat(problem.path("detail").asText()).contains("Nothing was changed");
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotBlank();
        // Refused before the controller: no payment, and no idempotency key claimed either.
        assertThat(count("SELECT count(*) FROM payments WHERE account_id = ?", thirdVisitorAccount)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE merchant_id = ?", "visitor")).isEqualTo(keysBefore);

        // The same refusal through the browser chain, and reads still work throughout.
        assertThat(status(post("/ui/payments/authorizations").session(browser).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorization(thirdVisitorAccount, 100)))).isEqualTo(429);
        assertThat(status(get("/ui/payments?limit=5").session(browser))).isEqualTo(200);
        assertThat(status(get("/v1/accounts/" + visitorAccount).header("Authorization", VISITOR))).isEqualTo(200);
    }

    @Test
    void theHistoryCapLeavesRetriesReplayableAndConsumesNoKey() throws Exception {
        String firstKey = "visitor-first-" + UUID.randomUUID();
        assertThat(authorizeRaw(VISITOR, visitorAccount, 100, firstKey).getResponse().getStatus()).isEqualTo(201);
        authorize(VISITOR, visitorAccount, 100, 201);

        // Two payments is the cap. The third is refused with its own code, and no key is claimed.
        String refusedKey = "visitor-full-" + UUID.randomUUID();
        MvcResult full = authorizeRaw(VISITOR, visitorAccount, 100, refusedKey);
        assertThat(full.getResponse().getStatus()).isEqualTo(429);
        assertThat(json.readTree(full.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("DEMO_ACCOUNT_FULL");
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE merchant_id = 'visitor' AND idempotency_key = ?",
                refusedKey)).isZero();
        assertThat(count("SELECT count(*) FROM payments WHERE account_id = ?", visitorAccount)).isEqualTo(2);

        // A retry of the authorization that committed still replays its receipt, full or not.
        MvcResult retried = authorizeRaw(VISITOR, visitorAccount, 100, firstKey);
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(retried.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");

        // Another account is unaffected: the cap is per account, which is what bounds each history.
        // (The refused attempt and the retry both counted against the command budget, as any request
        // does; a minute later that window is empty again.)
        clock.advance(Duration.ofMinutes(1));
        UUID second = newAccount("visitor");
        authorize(VISITOR, second, 100, 201);
    }

    @Test
    void replayIsBoundedToOneInFlightAndAnHourlyAllowance() throws Exception {
        // The suite shares one database and another class leaves visitor jobs behind; both limits
        // are now counted from the rows, so start this test from none in flight and none this hour.
        jdbc.update("UPDATE replay_jobs SET status = 'COMPLETED', created_at = created_at - interval '2 hours' WHERE merchant_id = 'visitor'");
        String candidate = "public-candidate-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(status(post("/v1/policies").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionId\":\"" + candidate + "\",\"definition\":" + CANDIDATE + "}"))).isEqualTo(201);
        authorize(VISITOR, visitorAccount, 100, 201);

        // The replay worker is off in the test profile, so the first job stays PENDING: in flight.
        assertThat(startReplay(VISITOR, candidate).getResponse().getStatus()).isEqualTo(201);
        MvcResult second = startReplay(VISITOR, candidate);
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        assertThat(json.readTree(second.getResponse().getContentAsString()).path("detail").asText())
                .contains("already has a replay job running");

        // Once nothing is in flight, the hourly allowance is what answers. (Every request above also
        // spent a command; a minute on, that budget is out of the way and only the replay limits speak.)
        jdbc.update("UPDATE replay_jobs SET status = 'COMPLETED' WHERE merchant_id = 'visitor'");
        clock.advance(Duration.ofMinutes(1));
        assertThat(startReplay(VISITOR, candidate).getResponse().getStatus()).isEqualTo(201);
        jdbc.update("UPDATE replay_jobs SET status = 'COMPLETED' WHERE merchant_id = 'visitor'");
        MvcResult third = startReplay(VISITOR, candidate);
        assertThat(third.getResponse().getStatus()).isEqualTo(429);
        assertThat(json.readTree(third.getResponse().getContentAsString()).path("detail").asText())
                .contains("hourly allowance");
        // Reading existing jobs is not budgeted.
        assertThat(status(get("/v1/replay-jobs").header("Authorization", VISITOR))).isEqualTo(200);
    }

    @Test
    void reconciliationIsBoundedPerMinuteForTheVisitorOnly() throws Exception {
        assertThat(status(get("/v1/reconciliation").header("Authorization", VISITOR))).isEqualTo(200);
        assertThat(status(get("/v1/reconciliation").header("Authorization", VISITOR))).isEqualTo(200);
        assertThat(status(get("/v1/reconciliation").header("Authorization", VISITOR))).isEqualTo(429);
        for (int i = 0; i < 4; i++) {
            assertThat(status(get("/v1/reconciliation").header("Authorization", DEMO))).isEqualTo(200);
        }
    }

    @Test
    void aRefusedReconciliationNeverRunsTheReportWhicheverMethodAsksForIt() throws Exception {
        // HEAD dispatches to the GET handler in Spring MVC; only the body is dropped, not the work.
        // The budget is about the work, so it is charged whatever the method, on both chains.
        MockHttpSession browser = signIn("visitor", "visitor-test-password-1234");
        clearInvocations(reconciliation);
        assertThat(status(get("/ui/reconciliation").session(browser))).isEqualTo(200);
        assertThat(status(head("/v1/reconciliation").header("Authorization", VISITOR))).isEqualTo(200);
        verify(reconciliation, times(2)).forMerchant(eq("visitor"), any());

        for (int i = 0; i < 3; i++) {
            assertThat(status(head("/v1/reconciliation").header("Authorization", VISITOR))).isEqualTo(429);
            assertThat(status(head("/ui/reconciliation").session(browser))).isEqualTo(429);
            assertThat(status(get("/ui/reconciliation").session(browser))).isEqualTo(429);
        }
        verify(reconciliation, times(2)).forMerchant(eq("visitor"), any());
        verifyNoMoreInteractions(reconciliation);
    }

    // ----- authentication attempts -----

    @Test
    void repeatedFailedSignInsLockTheAddressOutOnBothChainsUntilTheWindowPasses() throws Exception {
        String attacker = "203.0.113.77";
        for (int i = 0; i < 3; i++) {
            assertThat(status(get("/v1/accounts/" + privateAccount)
                    .header("Authorization", basic("admin", "wrong-password-" + i)).with(from(attacker)))).isEqualTo(401);
        }
        // Budget spent: even the right password is not examined now, on either chain.
        MvcResult locked = mvc.perform(get("/v1/accounts/" + privateAccount)
                .header("Authorization", DEMO).with(from(attacker))).andReturn();
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(json.readTree(locked.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("AUTHENTICATION_RATE_LIMITED");
        assertThat(locked.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(mvc.perform(post("/ui/session").with(csrf()).with(from(attacker))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("username=demo-merchant&password=demo-test-password-123"))
                .andReturn().getResponse().getStatus()).isEqualTo(429);

        // A different address is unaffected, and an unauthenticated read is not an attempt at all.
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", DEMO)
                .with(from("198.51.100.5")))).isEqualTo(200);
        assertThat(status(get("/actuator/health/liveness").with(from(attacker)))).isEqualTo(200);
    }

    @Test
    void aSuccessfulSignInClearsAnAddressesFailures() throws Exception {
        String careless = "203.0.113.90";
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("demo-merchant", "typo-password-0000")).with(from(careless)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("demo-merchant", "typo-password-0001")).with(from(careless)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", DEMO).with(from(careless)))).isEqualTo(200);
        // The count restarted: two more mistakes do not lock the address.
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("demo-merchant", "typo-password-0002")).with(from(careless)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("demo-merchant", "typo-password-0003")).with(from(careless)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", DEMO).with(from(careless)))).isEqualTo(200);
    }

    @Test
    void anAnonymousRequestCarryingAStrayBasicHeaderDoesNotClearAnAddressesFailures() throws Exception {
        // The browser chain ignores Basic on purpose, so an anonymous GET /ui/identity with any
        // Authorization header is a 200 that authenticated nobody. It must not count as a success.
        String guesser = "203.0.113.41";
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("admin", "guess-1-aaaaaaaaaaaa")).with(from(guesser)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("admin", "guess-2-aaaaaaaaaaaa")).with(from(guesser)))).isEqualTo(401);
        // Under budget, the anonymous request goes through - and is a 200 that authenticated nobody.
        assertThat(status(get("/ui/identity")
                .header("Authorization", basic("admin", "not-even-checked-here")).with(from(guesser)))).isEqualTo(200);
        // If that 200 had been taken for a success, this third guess would have been the first of a
        // fresh budget. It is the third of the only one there is.
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("admin", "guess-3-aaaaaaaaaaaa")).with(from(guesser)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("admin", "guess-4-aaaaaaaaaaaa")).with(from(guesser))))
                .as("a stray Basic header on an anonymous 200 is not a successful authentication").isEqualTo(429);
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", ADMIN).with(from(guesser)))).isEqualTo(429);
        // A locked address may still ask the anonymous question without a credential; that is not an
        // attempt. With a credential attached it is, and is refused like any other.
        assertThat(status(get("/ui/identity").with(from(guesser)))).isEqualTo(200);
        assertThat(status(get("/ui/identity")
                .header("Authorization", basic("admin", "still-not-checked")).with(from(guesser)))).isEqualTo(429);
    }

    @Test
    void thePublicVisitorSigningInDoesNotClearFailuresAgainstPrivateAccounts() throws Exception {
        // The visitor's password is public. Authenticating as it proves nothing about who is guessing
        // the administrator's password from the same address, so it must not reset that count.
        String shared = "203.0.113.42";
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("admin", "guess-1-xxxxxxxxxxxx")).with(from(shared)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("admin", "guess-2-xxxxxxxxxxxx")).with(from(shared)))).isEqualTo(401);
        // A genuine, successful visitor authentication on each chain.
        assertThat(status(get("/v1/accounts/" + visitorAccount).header("Authorization", VISITOR).with(from(shared)))).isEqualTo(200);
        assertThat(mvc.perform(post("/ui/session").with(csrf()).with(from(shared))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("username=visitor&password=visitor-test-password-1234")).andReturn().getResponse().getStatus()).isEqualTo(200);
        // One more wrong administrator password spends the budget of three.
        assertThat(status(get("/v1/accounts/" + privateAccount)
                .header("Authorization", basic("admin", "guess-3-xxxxxxxxxxxx")).with(from(shared)))).isEqualTo(401);
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", ADMIN).with(from(shared))))
                .as("visitor successes do not launder administrator guesses").isEqualTo(429);
    }

    @Test
    void aLockedAddressRecoversOnceTheWindowPasses() throws Exception {
        String impatient = "203.0.113.43";
        for (int i = 0; i < 3; i++) {
            assertThat(status(get("/v1/accounts/" + privateAccount)
                    .header("Authorization", basic("admin", "guess-" + i + "-yyyyyyyyyyyy")).with(from(impatient)))).isEqualTo(401);
        }
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", ADMIN).with(from(impatient)))).isEqualTo(429);
        clock.advance(properties.authFailureWindow());
        assertThat(status(get("/v1/accounts/" + privateAccount).header("Authorization", DEMO).with(from(impatient))))
                .as("the window passed; a right password from that address works again").isEqualTo(200);
    }

    // ----- what the public can see of the instance -----

    @Test
    void healthProbesStayPublicAndTheAsyncDetailsBecomeAnOperatorRead() throws Exception {
        assertThat(status(get("/actuator/health/liveness"))).isEqualTo(200);
        assertThat(status(get("/actuator/health/readiness"))).isEqualTo(200);
        // With details off, a probe says only its status.
        assertThat(mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getContentAsString())
                .doesNotContain("components", "db");

        for (String path : new String[] {"/actuator/health/async", "/actuator/health/async/asyncDelivery"}) {
            assertThat(status(get(path))).as("%s anonymous", path).isEqualTo(401);
            assertThat(status(get(path).header("Authorization", VISITOR))).as("%s visitor", path).isEqualTo(403);
            assertThat(status(get(path).header("Authorization", OPERATIONS))).as("%s operations", path).isEqualTo(200);
            assertThat(status(get(path).header("Authorization", ADMIN))).as("%s admin", path).isEqualTo(200);
        }
        String details = mvc.perform(get("/actuator/health/async").header("Authorization", OPERATIONS))
                .andReturn().getResponse().getContentAsString();
        assertThat(details).contains("asyncDelivery");
        // Nothing else under health is public either: only the two probes and the root.
        assertThat(status(get("/actuator/health/db"))).isIn(401, 404);
        assertThat(status(get("/actuator/health/readiness/db"))).isIn(401, 404);
    }

    @Test
    void theRunningRevisionIsPublicAndCarriesNoConfiguration() throws Exception {
        MvcResult info = mvc.perform(get("/actuator/info")).andReturn();
        assertThat(info.getResponse().getStatus()).isEqualTo(200);
        JsonNode build = json.readTree(info.getResponse().getContentAsString()).path("decisionrail");
        assertThat(build.path("commit").asText()).isEqualTo("test-commit-sha");
        assertThat(build.path("image").asText()).isEqualTo("ghcr.io/example/decisionrail:test");
        assertThat(build.path("synthetic").asBoolean()).isTrue();
        assertThat(build.path("latestMigration").asText()).matches("V\\d+");
        assertThat(info.getResponse().getContentAsString())
                .doesNotContain("password", "jdbc", "kafka", "bootstrap", "visitor-test");
    }

    @Test
    void faultInjectionHasNoHttpSurfaceForAnyone() throws Exception {
        for (String path : new String[] {"/v1/ops/faults", "/ui/ops/faults", "/actuator/faults", "/v1/faults"}) {
            for (String who : new String[] {VISITOR, ADMIN, OPERATIONS}) {
                int status = status(post(path).header("Authorization", who)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"));
                assertThat(status).as("%s as %s", path, who).isIn(403, 404, 405);
            }
        }
    }

    // ----- helpers -----

    private UUID newAccount(String merchant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor) VALUES (?, ?, 'CAD', 1000000, 1000000)",
                id, merchant);
        return id;
    }

    private static String authorization(UUID account, long amount) {
        return "{\"accountId\":\"" + account + "\",\"amountMinor\":" + amount + ",\"currency\":\"CAD\",\"country\":\"CA\"}";
    }

    private UUID authorize(String who, UUID account, long amount, int expected) throws Exception {
        MvcResult result = authorizeRaw(who, account, amount, UUID.randomUUID().toString());
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private MvcResult authorizeRaw(String who, UUID account, long amount, String key) throws Exception {
        return mvc.perform(post("/v1/payments/authorizations").header("Authorization", who)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(authorization(account, amount))).andReturn();
    }

    private void authorizeThroughBrowser(MockHttpSession browser, UUID account, long amount, int expected) throws Exception {
        MvcResult result = mvc.perform(post("/ui/payments/authorizations").session(browser).with(csrf())
                .header("Idempotency-Key", "ui-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(authorization(account, amount))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
    }

    private MvcResult startReplay(String who, String candidate) throws Exception {
        return mvc.perform(post("/v1/replay-jobs").header("Authorization", who)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateVersion\":\"" + candidate + "\",\"limit\":50}")).andReturn();
    }

    private MockHttpSession signIn(String username, String password) throws Exception {
        MvcResult result = mvc.perform(formLogin("/ui/session").user(username).password(password)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private long count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }

    /** Presents the request as coming from this client address, which is what the limiter keys on. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
