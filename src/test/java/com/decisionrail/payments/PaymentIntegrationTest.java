package com.decisionrail.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Exercises HTTP contracts and PostgreSQL's actual transaction/locking behavior.
 * Each test owns random accounts; append-only history is intentionally retained,
 * allowing this suite to run repeatedly against the same development test DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class PaymentIntegrationTest {
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String OTHER = basic("other-merchant", "other-test-password-123");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private UUID accountId;

    @BeforeEach
    void createIsolatedAccount() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        accountId = newAccount("demo-merchant", 1_000);
    }

    @Test
    void accountAndPaymentApisRequireAuthentication() throws Exception {
        Reply anonymous = reply(mvc.perform(get("/v1/accounts/{id}", accountId)).andReturn());
        assertProblem(anonymous, 401);
        assertThat(anonymous.body().path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(anonymous.body().path("requestId").asText()).isNotBlank();
        Reply wrongPassword = reply(mvc.perform(get("/v1/accounts/{id}", accountId)
                .header("Authorization", basic("demo-merchant", "wrong-password"))).andReturn());
        assertProblem(wrongPassword, 401);
    }

    @Test
    void merchantAndOperationsCredentialsHaveSeparatePermissions() throws Exception {
        String operations = basic("operations", "operations-test-password-123");
        assertProblem(authorize(accountId, 100, key(), operations), 403);
        assertProblem(read("/v1/accounts/" + accountId, operations), 403);
        assertProblem(read("/actuator/prometheus", DEMO), 403);
        assertThat(mvc.perform(get("/actuator/prometheus").header("Authorization", operations))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertFunds(accountId, DEMO, 1_000, 0, 1_000);
        assertThat(paymentCount(accountId)).isZero();
    }

    @Test
    void authorizationReservesFundsAndPersistsDecisionWithItsEvent() throws Exception {
        Reply reply = authorize(accountId, 200, key(), DEMO);
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().path("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(reply.body().path("amountMinor").asLong()).isEqualTo(200);
        assertThat(reply.body().path("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(reply.body().path("decision").isObject()).isTrue();
        assertThat(reply.body().path("decision").path("ruleSetVersion").asText()).isNotBlank();
        assertThat(reply.body().path("decision").path("reasons").isArray()).isTrue();
        assertThat(reply.body().path("decision").path("reasons").size()).isGreaterThan(0);
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(paymentCount(accountId)).isEqualTo(1);
        assertThat(eventCount(reply.id())).isEqualTo(1);
        assertThat(auditCount(reply.id())).isEqualTo(1);
        assertThat(ledger(reply.id(), DEMO).body().isEmpty()).isTrue();
    }

    @Test
    void validationRejectsInvalidAmountsAndMalformedInputsWithoutReservingFunds() throws Exception {
        List<Map<String, Object>> invalidBodies = new ArrayList<>();
        for (Object amount : List.of(0L, -1L, 1_000_000_000_001L, 1.5, 1.0, "100")) {
            Map<String, Object> body = authorizationBody(accountId, 100);
            body.put("amountMinor", amount);
            invalidBodies.add(body);
        }
        Map<String, Object> missingAmount = authorizationBody(accountId, 100);
        missingAmount.remove("amountMinor");
        invalidBodies.add(missingAmount);
        Map<String, Object> invalidCountry = authorizationBody(accountId, 100);
        invalidCountry.put("country", "CANADA");
        invalidBodies.add(invalidCountry);
        Map<String, Object> invalidCurrency = authorizationBody(accountId, 100);
        invalidCurrency.put("currency", "EUR");
        invalidBodies.add(invalidCurrency);
        Map<String, Object> invalidAccount = authorizationBody(accountId, 100);
        invalidAccount.put("accountId", "not-a-uuid");
        invalidBodies.add(invalidAccount);
        Map<String, Object> unknownField = authorizationBody(accountId, 100);
        unknownField.put("amountInDollars", 1);
        invalidBodies.add(unknownField);

        for (Map<String, Object> body : invalidBodies) {
            Reply reply = command("/v1/payments/authorizations", body, key(), DEMO);
            assertThat(reply.status()).as("invalid request: %s", body).isEqualTo(400);
            assertProblem(reply, 400);
            assertThat(reply.body().path("requestId").asText()).isNotBlank();
        }
        Reply missingKey = command("/v1/payments/authorizations",
                authorizationBody(accountId, 100), null, DEMO);
        assertThat(missingKey.status()).isEqualTo(400);
        assertFunds(accountId, DEMO, 1_000, 0, 1_000);
        assertThat(paymentCount(accountId)).isZero();
    }

    @Test
    void activeRulesExposeTheirVersionAndUnambiguousExpressionOperators() throws Exception {
        Reply active = read("/v1/rules/active", DEMO);
        assertThat(active.status()).isEqualTo(200);
        assertThat(active.body().path("version").asText()).isEqualTo("demo-v1");
        assertThat(active.body().path("homeCountry").asText()).isEqualTo("CA");
        assertThat(active.body().path("reviewThreshold").asInt()).isEqualTo(30);
        assertThat(active.body().path("declineThreshold").asInt()).isEqualTo(60);
        List<String> operators = new ArrayList<>();
        for (JsonNode rule : active.body().path("rules")) {
            JsonNode expression = rule.path("expression");
            operators.add(expression.path("operator").asText());
            if (expression.path("operator").asText().equals("ALL")) {
                assertThat(expression.path("children")).hasSize(2);
                assertThat(expression.path("children").get(0).path("operator").asText()).isEqualTo("AMOUNT_AT_LEAST");
                assertThat(expression.path("children").get(1).path("operator").asText()).isEqualTo("AMOUNT_LESS_THAN");
            }
        }
        assertThat(operators).containsExactlyInAnyOrder("COUNTRY_IN", "AMOUNT_AT_LEAST", "ALL", "COUNTRY_OUTSIDE");
        Reply authorization = authorize(accountId, 100, key(), DEMO);
        assertThat(authorization.body().path("decision").path("ruleSetVersion"))
                .isEqualTo(active.body().path("version"));
    }

    @Test
    void lifecycleCommandsRejectBodiesWithoutConsumingAnIdempotencyKey() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        for (String action : List.of("capture", "void")) {
            String idempotencyKey = key();
            Reply rejected = command("/v1/payments/" + authorization.id() + "/" + action,
                    Map.of("amountMinor", 100), idempotencyKey, DEMO);
            assertProblem(rejected, 400);
            assertThat(rejected.body().path("code").asText()).isEqualTo("UNEXPECTED_BODY");
            assertThat(idempotencyCount("demo-merchant", idempotencyKey)).isZero();
        }
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(state(read("/v1/payments/" + authorization.id(), DEMO))).isEqualTo("AUTHORIZED");
        assertThat(journalCount(authorization.id())).isZero();
        assertThat(eventCount(authorization.id())).isEqualTo(1);
    }

    @Test
    void accountCurrencyMismatchDoesNotMoveMoney() throws Exception {
        Map<String, Object> body = authorizationBody(accountId, 100);
        body.put("currency", "USD");
        Reply reply = command("/v1/payments/authorizations", body, key(), DEMO);
        assertProblem(reply, 422);
        assertThat(reply.body().path("code").asText()).isEqualTo("CURRENCY_MISMATCH");
        assertFunds(accountId, DEMO, 1_000, 0, 1_000);
        assertThat(paymentCount(accountId)).isZero();
    }

    @Test
    void policyReviewAndDeclineDoNotReserveMoney() throws Exception {
        UUID fundedAccount = newAccount("demo-merchant", 1_000_000);
        Reply review = authorize(fundedAccount, 100_000, key(), DEMO);
        assertThat(review.status()).isEqualTo(201);
        assertThat(review.body().path("status").asText()).isEqualTo("REVIEW");
        Reply decline = authorize(fundedAccount, 500_000, key(), DEMO);
        assertThat(decline.status()).isEqualTo(201);
        assertThat(decline.body().path("status").asText()).isEqualTo("DECLINED");
        Map<String, Object> restricted = authorizationBody(fundedAccount, 100);
        restricted.put("country", "ZZ");
        Reply countryDecline = command("/v1/payments/authorizations", restricted, key(), DEMO);
        assertThat(countryDecline.status()).isEqualTo(201);
        assertThat(countryDecline.body().path("status").asText()).isEqualTo("DECLINED");
        assertFunds(fundedAccount, DEMO, 1_000_000, 0, 1_000_000);
        assertThat(ledger(review.id(), DEMO).body().isEmpty()).isTrue();
        assertThat(ledger(decline.id(), DEMO).body().isEmpty()).isTrue();
    }

    @Test
    void concurrentAuthorizationsCannotOverspendAnAccount() throws Exception {
        List<Callable<Reply>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String idempotencyKey = key();
            requests.add(() -> authorize(accountId, 100, idempotencyKey, DEMO));
        }
        List<Reply> replies = concurrently(requests);
        assertThat(replies).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(201));
        assertThat(replies.stream().filter(reply -> state(reply).equals("AUTHORIZED"))).hasSize(10);
        assertThat(replies.stream().filter(reply -> state(reply).equals("DECLINED"))).hasSize(10);
        assertThat(replies.stream().filter(reply -> state(reply).equals("DECLINED")))
                .allSatisfy(reply -> assertThat(reply.body().path("failureCode").asText())
                        .isEqualTo("INSUFFICIENT_FUNDS"));
        assertFunds(accountId, DEMO, 1_000, 1_000, 0);
        assertThat(paymentCount(accountId)).isEqualTo(20);
    }

    @Test
    void simultaneousRetriesProduceOnePaymentOneHoldAndOneEvent() throws Exception {
        String idempotencyKey = key();
        List<Callable<Reply>> requests = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            requests.add(() -> authorize(accountId, 200, idempotencyKey, DEMO));
        }
        List<Reply> replies = concurrently(requests);
        Reply first = replies.getFirst();
        assertThat(replies).allSatisfy(reply -> {
            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.body()).isEqualTo(first.body());
        });
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(paymentCount(accountId)).isEqualTo(1);
        assertThat(eventCount(first.id())).isEqualTo(1);
        assertThat(auditCount(first.id())).isEqualTo(1);
        assertThat(idempotencyCount("demo-merchant", idempotencyKey)).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithAnotherPayloadIsAConflict() throws Exception {
        String idempotencyKey = key();
        Reply first = authorize(accountId, 200, idempotencyKey, DEMO);
        Reply conflict = authorize(accountId, 201, idempotencyKey, DEMO);
        assertProblem(conflict, 409);
        assertThat(authorize(accountId, 200, idempotencyKey, DEMO).body()).isEqualTo(first.body());
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(paymentCount(accountId)).isEqualTo(1);
    }

    @Test
    void idempotencyKeysAreScopedToMerchant() throws Exception {
        UUID otherAccount = newAccount("other-merchant", 1_000);
        String sharedKey = key();
        Reply demo = authorize(accountId, 200, sharedKey, DEMO);
        Reply other = authorize(otherAccount, 300, sharedKey, OTHER);
        assertThat(demo.status()).isEqualTo(201);
        assertThat(other.status()).isEqualTo(201);
        assertThat(demo.id()).isNotEqualTo(other.id());
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertFunds(otherAccount, OTHER, 1_000, 300, 700);
        assertThat(idempotencyCount("demo-merchant", sharedKey)).isEqualTo(1);
        assertThat(idempotencyCount("other-merchant", sharedKey)).isEqualTo(1);
    }

    @Test
    void merchantCannotReadOrMutateAnotherMerchantsAccountOrPayment() throws Exception {
        Reply payment = authorize(accountId, 200, key(), DEMO);
        assertProblem(read("/v1/accounts/" + accountId, OTHER), 404);
        assertProblem(read("/v1/payments/" + payment.id(), OTHER), 404);
        assertProblem(ledger(payment.id(), OTHER), 404);
        assertProblem(authorize(accountId, 100, key(), OTHER), 404);
        assertProblem(capture(payment.id(), key(), OTHER), 404);
        assertProblem(voidPayment(payment.id(), key(), OTHER), 404);
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(paymentCount(accountId)).isEqualTo(1);
        assertThat(state(read("/v1/payments/" + payment.id(), DEMO))).isEqualTo("AUTHORIZED");
    }

    @Test
    void captureCreatesOneBalancedJournalAndCannotDebitTwice() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        String captureKey = key();
        Reply captured = capture(authorization.id(), captureKey, DEMO);
        assertThat(captured.status()).isEqualTo(200);
        assertThat(state(captured)).isEqualTo("CAPTURED");
        Reply replay = capture(authorization.id(), captureKey, DEMO);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(captured.body());
        assertProblem(capture(authorization.id(), key(), DEMO), 409);
        assertFunds(accountId, DEMO, 800, 0, 800);
        assertThat(journalCount(authorization.id())).isEqualTo(1);
        assertBalancedLedger(authorization.id(), 200);
        assertThat(eventCount(authorization.id())).isEqualTo(2);
        assertThat(auditCount(authorization.id())).isEqualTo(2);
    }

    @Test
    void simultaneousCapturesCannotDebitTwice() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        List<Reply> replies = concurrently(List.of(
                () -> capture(authorization.id(), key(), DEMO),
                () -> capture(authorization.id(), key(), DEMO)));
        assertThat(replies.stream().map(Reply::status)).containsExactlyInAnyOrder(200, 409);
        assertFunds(accountId, DEMO, 800, 0, 800);
        assertThat(journalCount(authorization.id())).isEqualTo(1);
        assertBalancedLedger(authorization.id(), 200);
        assertThat(eventCount(authorization.id())).isEqualTo(2);
    }

    @Test
    void authorizationReplayAfterCaptureReturnsItsOriginalStoredResponse() throws Exception {
        String authorizationKey = key();
        Reply original = authorize(accountId, 200, authorizationKey, DEMO);
        assertThat(capture(original.id(), key(), DEMO).status()).isEqualTo(200);
        Reply replay = authorize(accountId, 200, authorizationKey, DEMO);
        assertThat(replay.status()).isEqualTo(original.status());
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(state(replay)).isEqualTo("AUTHORIZED");
        assertThat(state(read("/v1/payments/" + original.id(), DEMO))).isEqualTo("CAPTURED");
        assertFunds(accountId, DEMO, 800, 0, 800);
    }

    @Test
    void authorizationKeyCannotBeReusedForCapture() throws Exception {
        String authorizationKey = key();
        Reply authorized = authorize(accountId, 200, authorizationKey, DEMO);
        assertProblem(capture(authorized.id(), authorizationKey, DEMO), 409);
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(journalCount(authorized.id())).isZero();
    }

    @Test
    void voidReleasesTheHoldAndPreventsLaterCapture() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        String voidKey = key();
        Reply voided = voidPayment(authorization.id(), voidKey, DEMO);
        assertThat(voided.status()).isEqualTo(200);
        assertThat(state(voided)).isEqualTo("VOIDED");
        assertThat(voidPayment(authorization.id(), voidKey, DEMO).body()).isEqualTo(voided.body());
        assertProblem(voidPayment(authorization.id(), key(), DEMO), 409);
        assertProblem(capture(authorization.id(), key(), DEMO), 409);
        assertFunds(accountId, DEMO, 1_000, 0, 1_000);
        assertThat(ledger(authorization.id(), DEMO).body().isEmpty()).isTrue();
        assertThat(eventCount(authorization.id())).isEqualTo(2);
    }

    @Test
    void captureAndVoidRaceHasExactlyOneTerminalOutcome() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        List<Reply> replies = concurrently(List.of(
                () -> capture(authorization.id(), key(), DEMO),
                () -> voidPayment(authorization.id(), key(), DEMO)));
        assertThat(replies.stream().map(Reply::status)).containsExactlyInAnyOrder(200, 409);
        String finalState = state(read("/v1/payments/" + authorization.id(), DEMO));
        assertThat(finalState).isIn("CAPTURED", "VOIDED");
        if (finalState.equals("CAPTURED")) {
            assertFunds(accountId, DEMO, 800, 0, 800);
            assertThat(journalCount(authorization.id())).isEqualTo(1);
            assertBalancedLedger(authorization.id(), 200);
        } else {
            assertFunds(accountId, DEMO, 1_000, 0, 1_000);
            assertThat(journalCount(authorization.id())).isZero();
        }
        assertThat(eventCount(authorization.id())).isEqualTo(2);
    }

    @Test
    void capturedLedgerCannotBeUpdatedOrDeletedThroughSql() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        assertThat(capture(authorization.id(), key(), DEMO).status()).isEqualTo(200);
        UUID journalId = jdbc.queryForObject("SELECT id FROM ledger_journals WHERE payment_id = ?",
                UUID.class, authorization.id());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount_minor = amount_minor + 1 WHERE journal_id = ?", journalId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE journal_id = ?", journalId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_journals SET currency = 'USD' WHERE id = ?", journalId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_journals WHERE id = ?", journalId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertBalancedLedger(authorization.id(), 200);
    }

    @Test
    void aCapturedJournalRejectsAppendingEvenABalancedPair() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        assertThat(capture(authorization.id(), key(), DEMO).status()).isEqualTo(200);
        UUID journalId = jdbc.queryForObject("SELECT id FROM ledger_journals WHERE payment_id = ?",
                UUID.class, authorization.id());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ledger_entries (id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,'DEBIT',1),(?,?,?,'CREDIT',1)",
                UUID.randomUUID(), journalId, "test:extra-debit", UUID.randomUUID(), journalId, "test:extra-credit"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("sealed");
        assertBalancedLedger(authorization.id(), 200);
        assertFunds(accountId, DEMO, 800, 0, 800);
    }

    @Test
    void anEmptyJournalIsRejectedAtCommit() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        UUID journalId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO ledger_journals (id,payment_id,merchant_id,currency) VALUES (?,?,'demo-merchant','CAD')")) {
                    statement.setObject(1, journalId);
                    statement.setObject(2, authorization.id());
                    statement.executeUpdate();
                }
                assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
            } finally {
                connection.rollback();
            }
        }
        assertThat(journalCount(authorization.id())).isZero();
    }

    @Test
    void anUnbalancedJournalIsRejectedAtCommitAndLeavesNoRows() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        UUID journalId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO ledger_journals (id,payment_id,merchant_id,currency) VALUES (?,?,'demo-merchant','CAD')")) {
                    statement.setObject(1, journalId);
                    statement.setObject(2, authorization.id());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(
                        "INSERT INTO ledger_entries (id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,'DEBIT',200),(?,?,?,'CREDIT',199)")) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, journalId);
                    statement.setString(3, "test:customer");
                    statement.setObject(4, UUID.randomUUID());
                    statement.setObject(5, journalId);
                    statement.setString(6, "test:merchant");
                    statement.executeUpdate();
                }
                assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
            } finally {
                connection.rollback();
            }
        }
        assertThat(journalCount(authorization.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE journal_id = ?",
                Long.class, journalId)).isZero();
        assertFunds(accountId, DEMO, 1_000, 200, 800);
    }

    @Test
    void failedOutboxInsertRollsBackAuthorizationAndItsIdempotencyClaim() throws Exception {
        String authorizationKey = key();
        Map<String, Long> before = rowCounts();
        withFailingOutbox(accountId, () -> {
            Reply failed = authorize(accountId, 200, authorizationKey, DEMO);
            assertProblem(failed, 503);
            assertThat(failed.body().path("code").asText()).isEqualTo("STORAGE_UNAVAILABLE");
            return null;
        });
        assertFunds(accountId, DEMO, 1_000, 0, 1_000);
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(idempotencyCount("demo-merchant", authorizationKey)).isZero();
        Reply retry = authorize(accountId, 200, authorizationKey, DEMO);
        assertThat(retry.status()).isEqualTo(201);
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(paymentCount(accountId)).isEqualTo(1);
    }

    @Test
    void failedOutboxInsertRollsBackCaptureLedgerBalanceAndSavedResult() throws Exception {
        Reply authorization = authorize(accountId, 200, key(), DEMO);
        String captureKey = key();
        Map<String, Long> before = rowCounts();
        withFailingOutbox(accountId, () -> {
            assertProblem(capture(authorization.id(), captureKey, DEMO), 503);
            return null;
        });
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(state(read("/v1/payments/" + authorization.id(), DEMO))).isEqualTo("AUTHORIZED");
        assertFunds(accountId, DEMO, 1_000, 200, 800);
        assertThat(journalCount(authorization.id())).isZero();
        assertThat(idempotencyCount("demo-merchant", captureKey)).isZero();
        assertThat(capture(authorization.id(), captureKey, DEMO).status()).isEqualTo(200);
        assertFunds(accountId, DEMO, 800, 0, 800);
        assertBalancedLedger(authorization.id(), 200);
    }

    private UUID newAccount(String merchant, long openingBalance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                id, merchant, openingBalance, openingBalance);
        return id;
    }

    private Map<String, Object> authorizationBody(UUID account, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account);
        body.put("amountMinor", amount);
        body.put("currency", "CAD");
        body.put("country", "CA");
        return body;
    }

    private Reply authorize(UUID account, long amount, String key, String auth) throws Exception {
        return command("/v1/payments/authorizations", authorizationBody(account, amount), key, auth);
    }

    private Reply capture(UUID paymentId, String key, String auth) throws Exception {
        return command("/v1/payments/" + paymentId + "/capture", null, key, auth);
    }

    private Reply voidPayment(UUID paymentId, String key, String auth) throws Exception {
        return command("/v1/payments/" + paymentId + "/void", null, key, auth);
    }

    private Reply command(String path, Object body, String key, String auth) throws Exception {
        var request = post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.content(json.writeValueAsBytes(body));
        return reply(mvc.perform(request).andReturn());
    }

    private Reply read(String path, String auth) throws Exception {
        return reply(mvc.perform(get(path).header("Authorization", auth)).andReturn());
    }

    private Reply ledger(UUID paymentId, String auth) throws Exception {
        return read("/v1/payments/" + paymentId + "/ledger", auth);
    }

    private Reply reply(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(),
                body.isBlank() ? json.nullNode() : json.readTree(body));
    }

    private void assertFunds(UUID id, String auth, long balance, long held, long available) throws Exception {
        Reply account = read("/v1/accounts/" + id, auth);
        assertThat(account.status()).isEqualTo(200);
        assertThat(account.body().path("balanceMinor").asLong()).isEqualTo(balance);
        assertThat(account.body().path("heldMinor").asLong()).isEqualTo(held);
        assertThat(account.body().path("availableMinor").asLong()).isEqualTo(available);
    }

    private void assertBalancedLedger(UUID paymentId, long amount) throws Exception {
        Reply reply = ledger(paymentId, DEMO);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().isArray()).isTrue();
        assertThat(reply.body()).hasSize(2);
        long debits = 0;
        long credits = 0;
        for (JsonNode entry : reply.body()) {
            assertThat(entry.path("currency").asText()).isEqualTo("CAD");
            assertThat(entry.path("ledgerAccount").asText()).isNotBlank();
            assertThat(entry.path("side").asText()).isIn("DEBIT", "CREDIT");
            if (entry.path("side").asText().equals("DEBIT")) debits += entry.path("amountMinor").asLong();
            else credits += entry.path("amountMinor").asLong();
        }
        assertThat(debits).isEqualTo(amount);
        assertThat(credits).isEqualTo(amount);
    }

    private void assertProblem(Reply reply, int status) {
        assertThat(reply.status()).as("problem body: %s", reply.body()).isEqualTo(status);
        assertThat(reply.body().path("code").asText()).isNotBlank();
        assertThat(reply.body().path("status").asInt()).isEqualTo(status);
    }

    private long paymentCount(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE account_id = ?", Long.class, account);
    }

    private long journalCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_journals WHERE payment_id = ?", Long.class, payment);
    }

    private long eventCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, payment);
    }

    private long auditCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE payment_id = ?", Long.class, payment);
    }

    private long idempotencyCount(String merchant, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE merchant_id = ? AND idempotency_key = ?",
                Long.class, merchant, key);
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("payments", "ledger_journals", "ledger_entries", "outbox_events", "audit_events", "idempotency_records")) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private <T> T withFailingOutbox(UUID account, Callable<T> action) throws Exception {
        // The injected fault targets only this test's account; other accounts remain usable.
        String name = "test_outbox_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF EXISTS (SELECT 1 FROM payments WHERE id = NEW.aggregate_id AND account_id = '" + account + "'::uuid) THEN "
                + "RAISE EXCEPTION 'injected outbox failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");
        try {
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION " + name + "()");
            try {
                return action.call();
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON outbox_events");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS " + name + "()");
        }
    }

    private List<Reply> concurrently(List<Callable<Reply>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            List<Future<Reply>> futures = new ArrayList<>();
            for (Callable<Reply> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent start gate timed out");
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) replies.add(future.get(30, TimeUnit.SECONDS));
            return replies;
        } finally {
            start.countDown();
        }
    }

    private static String state(Reply reply) {
        return reply.body().path("status").asText();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private record Reply(int status, JsonNode body) {
        UUID id() { return UUID.fromString(body.path("id").asText()); }
    }
}
