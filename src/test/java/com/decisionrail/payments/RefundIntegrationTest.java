package com.decisionrail.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The financial boundaries of returning captured money, against real PostgreSQL.
 *
 * <p>Every test creates its own account and its own payments, because the suite shares one database
 * and an assertion about "the balance" would otherwise be an assertion about what every other test
 * happened to leave behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundIntegrationTest {
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String OTHER = basic("other-merchant", "other-test-password-123");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired TransactionTemplate transactions;

    private UUID accountId;

    @BeforeEach
    void createIsolatedAccount() {
        accountId = newAccount("demo-merchant", 100_000);
    }

    // ----- the budget -----

    @Test
    void partialRefundsCanBeRepeatedUntilTheCaptureIsExhausted() throws Exception {
        UUID payment = captured(5_000);

        Reply first = refund(payment, 1_500, "customer returned one item", key(), DEMO);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().path("amountMinor").asLong()).isEqualTo(1_500);
        assertThat(first.body().path("returnedAmountMinor").asLong()).isEqualTo(1_500);
        assertThat(first.body().path("remainingRefundableMinor").asLong()).isEqualTo(3_500);
        assertThat(first.body().path("sequenceNumber").asInt()).isEqualTo(1);

        Reply second = refund(payment, 2_000, null, key(), DEMO);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().path("remainingRefundableMinor").asLong()).isEqualTo(1_500);
        assertThat(second.body().path("sequenceNumber").asInt()).isEqualTo(2);

        // The remainder, stated exactly, which is how a "refund everything left" request is expressed.
        Reply remainder = refund(payment, 1_500, null, key(), DEMO);
        assertThat(remainder.status()).isEqualTo(201);
        assertThat(remainder.body().path("remainingRefundableMinor").asLong()).isZero();

        // Opening balance is whole again: captured 5000, returned 5000.
        assertFunds(accountId, 100_000, 0);
        Reply summary = read("/v1/payments/" + payment + "/returns", DEMO);
        assertThat(summary.body().path("returnedAmountMinor").asLong()).isEqualTo(5_000);
        assertThat(summary.body().path("refundable").asBoolean()).isFalse();
        assertThat(summary.body().path("unavailableReason").asText()).isEqualTo("FULLY_RETURNED");
        assertThat(summary.body().path("returns")).hasSize(3);
    }

    @Test
    void aRefundAboveTheRemainingAmountIsRefusedAndChangesNothing() throws Exception {
        UUID payment = captured(5_000);
        refund(payment, 4_000, null, key(), DEMO);

        Reply tooMuch = refund(payment, 1_001, null, key(), DEMO);
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().path("code").asText()).isEqualTo("RETURN_EXCEEDS_REFUNDABLE");

        assertFunds(accountId, 99_000, 0);
        assertThat(returnCount(payment)).isEqualTo(1);
        assertThat(returnJournalCount(payment)).isEqualTo(1);
    }

    @Test
    void onlyACapturedPaymentCanBeReturned() throws Exception {
        UUID authorized = authorized(1_000);
        assertThat(refund(authorized, 100, null, key(), DEMO).body().path("code").asText())
                .isEqualTo("INVALID_PAYMENT_STATE");

        UUID voided = authorized(1_000);
        command("/v1/payments/" + voided + "/void", null, key(), DEMO);
        assertThat(refund(voided, 100, null, key(), DEMO).status()).isEqualTo(409);
        assertThat(reverse(voided, null, key(), DEMO).status()).isEqualTo(409);

        // Nothing was written for either attempt.
        assertThat(returnCount(authorized)).isZero();
        assertThat(returnCount(voided)).isZero();
    }

    @Test
    void anAmountOutsideTheSupportedRangeIsRefusedBeforeAnythingIsRead() throws Exception {
        UUID payment = captured(5_000);
        assertThat(refund(payment, 0, null, key(), DEMO).status()).isEqualTo(400);
        assertThat(refund(payment, -1, null, key(), DEMO).status()).isEqualTo(400);
        assertThat(refund(payment, 1_000_000_000_001L, null, key(), DEMO).status()).isEqualTo(400);
        assertThat(returnCount(payment)).isZero();
    }

    // ----- reversal -----

    @Test
    void aReversalReturnsTheWholeCaptureAndIsRefusedOnceAnythingHasBeenReturned() throws Exception {
        UUID reversible = captured(4_100);
        Reply reversal = reverse(reversible, "captured in error", key(), DEMO);
        assertThat(reversal.status()).isEqualTo(201);
        assertThat(reversal.body().path("returnType").asText()).isEqualTo("REVERSAL");
        assertThat(reversal.body().path("amountMinor").asLong()).isEqualTo(4_100);
        assertThat(reversal.body().path("remainingRefundableMinor").asLong()).isZero();

        // The chosen rule: a partial refund closes the reversal off entirely rather than silently
        // becoming a refund of the remainder. See docs/adr/0007.
        UUID partiallyRefunded = captured(4_100);
        refund(partiallyRefunded, 100, null, key(), DEMO);
        Reply refused = reverse(partiallyRefunded, null, key(), DEMO);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().path("code").asText()).isEqualTo("PAYMENT_NOT_REVERSIBLE");

        Reply summary = read("/v1/payments/" + partiallyRefunded + "/returns", DEMO);
        assertThat(summary.body().path("reversible").asBoolean()).isFalse();
        assertThat(summary.body().path("refundable").asBoolean()).isTrue();
        assertThat(summary.body().path("unavailableReason").asText()).isEqualTo("PARTIALLY_RETURNED");

        // The remainder is still available, as a refund.
        assertThat(refund(partiallyRefunded, 4_000, null, key(), DEMO).status()).isEqualTo(201);
    }

    @Test
    void aReversalMustNotStateAnAmount() throws Exception {
        UUID payment = captured(2_000);
        Reply withAmount = command("/v1/payments/" + payment + "/reversal",
                Map.of("amountMinor", 1_000), key(), DEMO);
        assertThat(withAmount.status()).isEqualTo(400);
        assertThat(withAmount.body().path("code").asText()).isEqualTo("INVALID_RETURN_INPUT");
        assertThat(returnCount(payment)).isZero();
    }

    // ----- concurrency -----

    @Test
    void concurrentRefundsCannotTogetherReturnMoreThanWasCaptured() throws Exception {
        UUID payment = captured(1_000);
        int attempts = 8;
        long each = 300;

        List<Reply> replies = inParallel(attempts, () -> refund(payment, each, null, key(), DEMO));

        long accepted = replies.stream().filter(reply -> reply.status() == 201).count();
        long refused = replies.stream().filter(reply -> reply.status() == 422).count();
        // 1000 / 300 = three whole refunds; the rest must be refused rather than over-crediting.
        assertThat(accepted).isEqualTo(3);
        assertThat(refused).isEqualTo(attempts - 3);

        assertThat(returnedTotal(payment)).isEqualTo(900);
        assertThat(recordedReturnedTotal(payment)).isEqualTo(900);
        assertThat(returnJournalCount(payment)).isEqualTo(3);
        assertFunds(accountId, 99_900, 0);
    }

    @Test
    void concurrentRetriesOfOneKeyProduceOneRefund() throws Exception {
        UUID payment = captured(1_000);
        String key = key();

        List<Reply> replies = inParallel(6, () -> refund(payment, 250, "same command", key, DEMO));

        assertThat(replies).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(201));
        List<String> returnIds = replies.stream().map(reply -> reply.body().path("returnId").asText()).distinct().toList();
        assertThat(returnIds).as("every retry must describe the same return operation").hasSize(1);
        assertThat(returnCount(payment)).isEqualTo(1);
        assertThat(returnJournalCount(payment)).isEqualTo(1);
        assertFunds(accountId, 99_250, 0);
    }

    @Test
    void otherPaymentsOnTheSameAccountKeepTheirHoldsWhileARefundCommits() throws Exception {
        UUID toRefund = captured(3_000);
        UUID stillAuthorized = authorized(2_500);
        UUID alsoCaptured = captured(1_000);

        assertFunds(accountId, 96_000, 2_500);
        refund(toRefund, 3_000, null, key(), DEMO);

        // The refund credits the balance back and leaves the unrelated hold exactly where it was.
        assertFunds(accountId, 99_000, 2_500);
        assertThat(statusOf(stillAuthorized)).isEqualTo("AUTHORIZED");
        assertThat(returnCount(alsoCaptured)).isZero();

        // And the still-authorized payment can still be captured out of the restored balance.
        assertThat(command("/v1/payments/" + stillAuthorized + "/capture", null, key(), DEMO).status()).isEqualTo(200);
        assertFunds(accountId, 96_500, 0);
    }

    // ----- idempotency -----

    @Test
    void reusingAKeyForADifferentRequestIsRejected() throws Exception {
        UUID payment = captured(5_000);
        UUID otherPayment = captured(5_000);
        String key = key();
        assertThat(refund(payment, 1_000, "reason one", key, DEMO).status()).isEqualTo(201);

        assertThat(refund(payment, 1_001, "reason one", key, DEMO).status()).isEqualTo(409);
        assertThat(refund(payment, 1_000, "reason two", key, DEMO).status()).isEqualTo(409);
        assertThat(refund(otherPayment, 1_000, "reason one", key, DEMO).status()).isEqualTo(409);
        assertThat(reverse(payment, "reason one", key, DEMO).status()).isEqualTo(409);

        assertThat(returnCount(payment)).isEqualTo(1);
        assertThat(returnCount(otherPayment)).isZero();
    }

    @Test
    void anAbsentReasonAndTheLiteralTextNullAreDifferentRequests() throws Exception {
        // Java renders a null reference as the four characters "null" when concatenated, so a
        // fingerprint built by concatenation cannot tell "no reason was given" from "the reason is the
        // word null". Two different requests then share one identity.
        UUID payment = captured(5_000);
        String key = key();
        Reply absent = refund(payment, 1_000, null, key, DEMO);
        assertThat(absent.status()).isEqualTo(201);

        Reply literal = refund(payment, 1_000, "null", key, DEMO);
        assertThat(literal.status())
                .as("a reason of \"null\" is not the same request as no reason at all")
                .isEqualTo(409);
        assertThat(literal.body().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // And in the other direction, on a key that first carried the literal text.
        UUID other = captured(5_000);
        String otherKey = key();
        assertThat(refund(other, 1_000, "null", otherKey, DEMO).status()).isEqualTo(201);
        assertThat(refund(other, 1_000, null, otherKey, DEMO).status())
                .as("no reason at all is not the same request as a reason of \"null\"")
                .isEqualTo(409);

        // Neither attempt may have moved money a second time.
        assertThat(returnCount(payment)).isEqualTo(1);
        assertThat(returnCount(other)).isEqualTo(1);
    }

    @Test
    void aReversalTellsAnAbsentReasonFromTheLiteralTextNullToo() throws Exception {
        UUID payment = captured(3_000);
        String key = key();
        assertThat(reverse(payment, null, key, DEMO).status()).isEqualTo(201);
        assertThat(reverse(payment, "null", key, DEMO).status()).isEqualTo(409);
        assertThat(returnCount(payment)).isEqualTo(1);
    }

    @Test
    void aKeyStoredUnderTheOldFingerprintStillReplaysItsReceipt() throws Exception {
        // A return that committed before the encoding changed carries the old hash. Retrying it must
        // still return its receipt: a fingerprint change that turns legitimate retries into conflicts
        // would strand exactly the commands idempotency exists to protect.
        UUID payment = captured(5_000);
        String key = key();
        Reply original = refund(payment, 1_200, "customer asked", key, DEMO);
        assertThat(original.status()).isEqualTo(201);
        rewriteStoredHashToTheOldEncoding(key, "REFUND|" + payment + "|1200|customer asked");

        Reply retried = refund(payment, 1_200, "customer asked", key, DEMO);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body()).isEqualTo(original.body());
        assertThat(returnCount(payment)).as("a replay creates no second return").isEqualTo(1);
        assertThat(returnJournalCount(payment)).isEqualTo(1);
        assertThat(outboxCount(payment)).as("and no second event").isEqualTo(3);
        assertFunds(accountId, 100_000 - 5_000 + 1_200, 0);

        // A genuinely different request against that same legacy key is still refused.
        assertThat(refund(payment, 1_300, "customer asked", key, DEMO).status()).isEqualTo(409);
    }

    @Test
    void theOldFingerprintFallbackDoesNotCarryTheAmbiguityForward() throws Exception {
        // The old hash cannot tell an absent reason from the literal text "null", so accepting it on
        // its own would reintroduce the defect for every key that predates the fix. The stored receipt
        // records which of the two actually committed, and that is what decides.
        UUID payment = captured(5_000);
        String key = key();
        assertThat(refund(payment, 1_000, null, key, DEMO).status()).isEqualTo(201);
        rewriteStoredHashToTheOldEncoding(key, "REFUND|" + payment + "|1000|null");

        Reply literal = refund(payment, 1_000, "null", key, DEMO);
        assertThat(literal.status())
                .as("the old hash matches, but the receipt says the reason was absent")
                .isEqualTo(409);
        assertThat(returnCount(payment)).isEqualTo(1);

        // The command that really did commit under that legacy hash still replays.
        assertThat(refund(payment, 1_000, null, key, DEMO).status()).isEqualTo(201);
        assertThat(returnCount(payment)).isEqualTo(1);
    }

    @Test
    void aBlankReasonIsTheSameRequestAsNoReasonAndWhitespaceIsStripped() throws Exception {
        UUID payment = captured(5_000);
        String key = key();
        Reply blank = refund(payment, 900, "   ", key, DEMO);
        assertThat(blank.status()).isEqualTo(201);
        assertThat(blank.body().path("reason").isNull()).as("blank is stored as absent").isTrue();
        // Absent, empty and whitespace-only are one request.
        assertThat(refund(payment, 900, null, key, DEMO).status()).isEqualTo(201);
        assertThat(refund(payment, 900, "", key, DEMO).status()).isEqualTo(201);

        // Surrounding whitespace is stripped, so these are one reason rather than two.
        UUID other = captured(5_000);
        String otherKey = key();
        Reply padded = refund(other, 800, "  duplicate charge  ", otherKey, DEMO);
        assertThat(padded.body().path("reason").asText()).isEqualTo("duplicate charge");
        assertThat(refund(other, 800, "duplicate charge", otherKey, DEMO).status()).isEqualTo(201);
        assertThat(returnCount(other)).isEqualTo(1);

        // A different ordinary reason is still a different request.
        assertThat(refund(other, 800, "customer returned it", otherKey, DEMO).status()).isEqualTo(409);
    }

    /** Puts a committed key back into the shape the previous concatenating encoding would have left. */
    private void rewriteStoredHashToTheOldEncoding(String key, String legacyFingerprint) {
        String hash = java.util.HexFormat.of().formatHex(sha256(legacyFingerprint));
        int updated = jdbc.update(
                "UPDATE idempotency_records SET request_hash = ? WHERE merchant_id = 'demo-merchant' AND idempotency_key = ?",
                hash, key);
        assertThat(updated).as("the key to rewrite must exist").isEqualTo(1);
    }

    private static byte[] sha256(String input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void theSameKeyTextIsIsolatedBetweenMerchants() throws Exception {
        UUID mine = captured(2_000);
        UUID theirs = capturedFor("other-merchant", newAccount("other-merchant", 50_000), 2_000);
        String sharedText = key();

        Reply ours = refund(mine, 500, null, sharedText, DEMO);
        Reply theirsReply = refund(theirs, 700, null, sharedText, OTHER);

        assertThat(ours.status()).isEqualTo(201);
        assertThat(theirsReply.status()).isEqualTo(201);
        assertThat(ours.body().path("returnId").asText()).isNotEqualTo(theirsReply.body().path("returnId").asText());
        assertThat(theirsReply.body().path("amountMinor").asLong()).isEqualTo(700);
    }

    @Test
    void aHistoricalReceiptIsNotRebuiltFromThePaymentsCurrentState() throws Exception {
        UUID payment = captured(5_000);
        String firstKey = key();
        Reply original = refund(payment, 1_000, null, firstKey, DEMO);
        assertThat(original.body().path("returnedAmountMinor").asLong()).isEqualTo(1_000);
        assertThat(original.body().path("remainingRefundableMinor").asLong()).isEqualTo(4_000);

        // More money comes back afterwards. The first receipt must still describe its own moment.
        refund(payment, 2_000, null, key(), DEMO);
        refund(payment, 500, null, key(), DEMO);

        Reply replayed = refund(payment, 1_000, null, firstKey, DEMO);
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.body()).isEqualTo(original.body());
        assertThat(replayed.body().path("returnedAmountMinor").asLong()).isEqualTo(1_000);
        assertThat(replayed.body().path("remainingRefundableMinor").asLong()).isEqualTo(4_000);

        // The live view does reflect everything, which is what makes the receipt's stability meaningful.
        assertThat(read("/v1/payments/" + payment + "/returns", DEMO).body()
                .path("remainingRefundableMinor").asLong()).isEqualTo(1_500);
    }

    @Test
    void aHistoricalPaymentResponseStillDecodesAfterRefundsExist() throws Exception {
        String authorizeKey = key();
        Reply authorization = authorize(accountId, 5_000, authorizeKey, DEMO);
        UUID payment = authorization.id();
        String captureKey = key();
        Reply capture = command("/v1/payments/" + payment + "/capture", null, captureKey, DEMO);
        refund(payment, 1_000, null, key(), DEMO);

        // Replaying an authorize and a capture key after a refund still returns a payment, not a
        // receipt, and returns the exact bytes each stored.
        assertThat(authorize(accountId, 5_000, authorizeKey, DEMO).body()).isEqualTo(authorization.body());
        assertThat(command("/v1/payments/" + payment + "/capture", null, captureKey, DEMO).body())
                .isEqualTo(capture.body());
    }

    @Test
    void aCommittedRefundWhoseResponseWasLostIsRecoveredByTheSameKey() throws Exception {
        UUID payment = captured(5_000);
        String key = key();

        // The command commits, and the caller never sees the answer. Recovery is resending the same
        // key, which is the only safe thing a caller with an unknown outcome can do.
        ReturnReceiptView committed = payments.returnFunds("demo-merchant", key,
                new ReturnCommand(payment, ReturnType.REFUND, 1_250L, "lost response")).body();

        Reply recovered = refund(payment, 1_250, "lost response", key, DEMO);
        assertThat(recovered.status()).isEqualTo(201);
        assertThat(recovered.body().path("returnId").asText()).isEqualTo(committed.returnId().toString());
        assertThat(returnCount(payment)).as("recovery must not refund twice").isEqualTo(1);
        assertFunds(accountId, 96_250, 0);
    }

    // ----- return history -----

    @Test
    void everyReturnIsReachableThroughHistoryEvenPastTheOldCap() throws Exception {
        // 201 returns: one more than the number the old implementation would load at all. Its newest
        // operation and that operation's journal were unreachable through the history, while the
        // payment's totals counted them - so the screen showed 200 returns for a payment that had 201.
        UUID payment = captured(20_100);
        for (int i = 0; i < 201; i++) {
            assertThat(refund(payment, 100, "return " + i, key(), DEMO).status()).isEqualTo(201);
        }
        assertThat(returnCount(payment)).isEqualTo(201);

        Reply first = read("/v1/payments/" + payment + "/returns", DEMO);
        assertThat(first.status()).isEqualTo(200);
        // The total describes the payment; the page describes the page. They are different numbers and
        // both are stated.
        assertThat(first.body().path("returnCount").asLong()).isEqualTo(201);
        assertThat(first.body().path("returns")).hasSize(50);
        assertThat(first.body().path("pageLimit").asInt()).isEqualTo(50);
        assertThat(first.body().path("returnedAmountMinor").asLong()).isEqualTo(20_100);
        assertThat(first.body().path("remainingRefundableMinor").asLong()).isZero();

        // Newest first, so the most recent operation is on the first page rather than past the end.
        assertThat(first.body().path("returns").get(0).path("sequenceNumber").asInt()).isEqualTo(201);
        assertThat(first.body().path("returns").get(0).path("journalId").isNull()).isFalse();

        // Walk the whole history. Every sequence number must appear exactly once.
        List<Integer> seen = new ArrayList<>();
        JsonNode page = first.body();
        int pages = 0;
        while (true) {
            page.path("returns").forEach(entry -> seen.add(entry.path("sequenceNumber").asInt()));
            pages++;
            String cursor = page.path("nextCursor").asText(null);
            if (cursor == null || cursor.isBlank()) break;
            assertThat(pages).as("paging must terminate").isLessThan(20);
            Reply next = read("/v1/payments/" + payment + "/returns?cursor=" + cursor, DEMO);
            assertThat(next.status()).isEqualTo(200);
            page = next.body();
        }
        assertThat(seen).as("no entry is omitted or duplicated").hasSize(201).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 201).boxed().sorted(java.util.Comparator.reverseOrder()).toList());
    }

    @Test
    void aReturnCommittedWhilePagingDoesNotDisturbThePagesAlreadyRead() throws Exception {
        UUID payment = captured(5_000);
        for (int i = 0; i < 6; i++) refund(payment, 100, "before " + i, key(), DEMO);

        Reply first = read("/v1/payments/" + payment + "/returns?limit=3", DEMO);
        assertThat(first.body().path("returns")).hasSize(3);
        String cursor = first.body().path("nextCursor").asText();

        // A new return commits between pages. Keyset paging on the sequence number means it lands ahead
        // of what has already been read rather than shifting a boundary inside it.
        refund(payment, 100, "during paging", key(), DEMO);

        Reply second = read("/v1/payments/" + payment + "/returns?limit=3&cursor=" + cursor, DEMO);
        List<Integer> secondPage = new ArrayList<>();
        second.body().path("returns").forEach(entry -> secondPage.add(entry.path("sequenceNumber").asInt()));
        assertThat(secondPage).containsExactly(3, 2, 1);
        assertThat(second.body().path("returnCount").asLong())
                .as("the total is current even though the page is a continuation").isEqualTo(7);
    }

    @Test
    void aReturnHistoryCursorIsScopedToItsOwnPayment() throws Exception {
        UUID mine = captured(2_000);
        UUID other = captured(2_000);
        refund(mine, 100, null, key(), DEMO);
        refund(mine, 100, null, key(), DEMO);
        refund(other, 100, null, key(), DEMO);

        String cursor = read("/v1/payments/" + mine + "/returns?limit=1", DEMO).body().path("nextCursor").asText();
        // A cursor names the payment it was minted for, so it cannot silently reposition inside another.
        Reply crossed = read("/v1/payments/" + other + "/returns?cursor=" + cursor, DEMO);
        assertThat(crossed.status()).isEqualTo(400);
        assertThat(crossed.body().path("code").asText()).isEqualTo("INVALID_RETURN_CURSOR");
        assertThat(read("/v1/payments/" + mine + "/returns?cursor=not-a-cursor", DEMO).status()).isEqualTo(400);

        // And another merchant cannot read this history at all, cursor or no cursor.
        assertThat(read("/v1/payments/" + mine + "/returns", OTHER).status()).isEqualTo(404);
        assertThat(read("/v1/payments/" + mine + "/returns?limit=0", DEMO).status()).isEqualTo(400);
        assertThat(read("/v1/payments/" + mine + "/returns?limit=201", DEMO).status()).isEqualTo(400);
    }

    @Test
    void eligibilityComesFromTheCountNotFromWhetherAPageIsEmpty() throws Exception {
        UUID payment = captured(2_000);
        refund(payment, 500, null, key(), DEMO);

        // Paged past the end: the history page is empty, but the payment has been partly returned and
        // must not be offered as reversible.
        String cursor = new ReturnCursor(payment, 1).encode();
        Reply pastTheEnd = read("/v1/payments/" + payment + "/returns?cursor=" + cursor, DEMO);
        assertThat(pastTheEnd.body().path("returns")).isEmpty();
        assertThat(pastTheEnd.body().path("returnCount").asLong()).isEqualTo(1);
        assertThat(pastTheEnd.body().path("reversible").asBoolean()).isFalse();
        assertThat(pastTheEnd.body().path("refundable").asBoolean()).isTrue();
        assertThat(pastTheEnd.body().path("unavailableReason").asText()).isEqualTo("PARTIALLY_RETURNED");
    }

    // ----- atomicity -----

    @Test
    void aFailureAfterTheFinancialWritesRollsEveryOneOfThemBack() throws Exception {
        UUID payment = captured(5_000);
        long journalsBefore = journalCount();

        // The refund runs its writes and then the surrounding transaction fails. Everything it wrote
        // takes part in that transaction, so a partial financial effect would be visible here.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            payments.returnFunds("demo-merchant", key(),
                    new ReturnCommand(payment, ReturnType.REFUND, 2_000L, "will not commit"));
            throw new IllegalStateException("Simulated failure after the financial writes");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(returnCount(payment)).isZero();
        assertThat(journalCount()).isEqualTo(journalsBefore);
        assertThat(recordedReturnedTotal(payment)).isZero();
        assertThat(outboxCount(payment)).as("the event intent rolled back with the money").isEqualTo(2);
        assertFunds(accountId, 95_000, 0);

        // And the key is free, because nothing committed under it.
        assertThat(refund(payment, 2_000, "second attempt", key(), DEMO).status()).isEqualTo(201);
    }

    // ----- ownership -----

    @Test
    void anotherMerchantCanNeitherSeeNorReturnThisPayment() throws Exception {
        UUID payment = captured(5_000);

        assertThat(refund(payment, 100, null, key(), OTHER).status()).isEqualTo(404);
        assertThat(reverse(payment, null, key(), OTHER).status()).isEqualTo(404);
        assertThat(read("/v1/payments/" + payment + "/returns", OTHER).status()).isEqualTo(404);
        assertThat(returnCount(payment)).isZero();

        String operations = basic("operations", "operations-test-password-123");
        assertThat(refund(payment, 100, null, key(), operations).status()).isEqualTo(403);
        assertThat(read("/v1/payments/" + payment + "/returns", operations).status()).isEqualTo(403);
    }

    // ----- the ledger -----

    @Test
    void theCaptureJournalIsUntouchedByCompensationAndTheReturnJournalMirrorsIt() throws Exception {
        UUID payment = captured(5_000);
        Map<String, Object> captureJournal = captureJournalRow(payment);

        Reply refunded = refund(payment, 2_000, null, key(), DEMO);
        UUID returnJournal = UUID.fromString(refunded.body().path("journalId").asText());

        assertThat(captureJournalRow(payment))
                .as("the capture journal is historical evidence and does not change")
                .isEqualTo(captureJournal);

        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT ledger_account, side, amount_minor FROM ledger_entries WHERE journal_id = ? ORDER BY side",
                returnJournal);
        assertThat(entries).hasSize(2);
        // Exactly the reverse of a capture: value leaves merchant clearing and returns to the wallet.
        assertThat(entries.get(0)).containsEntry("side", "CREDIT")
                .containsEntry("ledger_account", "wallet:" + accountId)
                .containsEntry("amount_minor", 2_000L);
        assertThat(entries.get(1)).containsEntry("side", "DEBIT")
                .containsEntry("ledger_account", "merchant-clearing:demo-merchant")
                .containsEntry("amount_minor", 2_000L);
    }

    @Test
    void aSecondJournalForOneReturnIsRefused() throws Exception {
        UUID payment = captured(5_000);
        UUID returnId = UUID.fromString(refund(payment, 2_000, null, key(), DEMO).body().path("returnId").asText());

        // This return already has its journal, so a second one is refused by uniqueness - which is what
        // this test is for, and what the amount and direction tests below must not accidentally hit.
        assertThatThrownBy(() -> writeReturnJournal(payment, returnId, 2_000,
                "merchant-clearing:demo-merchant", "wallet:" + accountId))
                .as("one journal per return operation")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger_journals_one_per_return");
        assertThat(returnJournalCount(payment)).isEqualTo(1);
    }

    @Test
    void aCorrectlyFormedReturnJournalIsAccepted() throws Exception {
        // The positive control. Without it the rejections below could be failing for a setup reason -
        // a missing row, a foreign key, a trigger this fixture never satisfies - and would look
        // identical to the protection they claim to demonstrate.
        UUID payment = captured(5_000);
        long journalsBefore = returnJournalCount(payment);

        attemptReturnWithJournal(payment, 500, 500, "merchant-clearing:demo-merchant", "wallet:" + accountId);

        assertThat(returnJournalCount(payment)).isEqualTo(journalsBefore + 1);
        assertThat(recordedReturnedTotal(payment)).isEqualTo(500);
        assertThat(returnedTotal(payment)).isEqualTo(500);
    }

    @Test
    void aReturnJournalForTheWrongAmountIsRefused() throws Exception {
        UUID payment = captured(5_000);

        // A fresh return with no journal yet, so the amount rule is what this reaches. The journal
        // balances perfectly at 400; it simply records an amount the return never returned.
        assertThatThrownBy(() -> attemptReturnWithJournal(payment, 500, 400,
                "merchant-clearing:demo-merchant", "wallet:" + accountId))
                .as("the journal amount must equal the return operation's amount")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("matching its RETURN operation");

        // Rejected at commit, so the return row went with it.
        assertThat(returnCount(payment)).isZero();
        assertThat(recordedReturnedTotal(payment)).isZero();
        assertFunds(accountId, 95_000, 0);
    }

    @Test
    void aReturnJournalMovingMoneyTheWrongWayIsRefused() throws Exception {
        UUID payment = captured(5_000);

        // Balanced, correct amount, correct accounts - and the sides swapped, so it credits merchant
        // clearing and debits the wallet. That is a capture wearing a return's name, and it would take
        // money from the account the return was meant to pay back.
        assertThatThrownBy(() -> attemptReturnWithJournal(payment, 500, 500,
                "wallet:" + accountId, "merchant-clearing:demo-merchant"))
                .as("a return must debit merchant clearing and credit the wallet")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("matching its RETURN operation");

        // And an entry naming an account belonging to neither side of this payment.
        assertThatThrownBy(() -> attemptReturnWithJournal(payment, 500, 500,
                "merchant-clearing:demo-merchant", "wallet:" + UUID.randomUUID()))
                .as("a return must credit this payment's own wallet")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("matching its RETURN operation");

        assertThat(returnCount(payment)).isZero();
        assertFunds(accountId, 95_000, 0);
    }

    @Test
    void theCaptureJournalStaysSealedAndSingular() throws Exception {
        UUID payment = captured(5_000);
        refund(payment, 2_000, null, key(), DEMO);
        UUID captureJournal = jdbc.queryForObject(
                "SELECT id FROM ledger_journals WHERE payment_id = ? AND journal_kind = 'CAPTURE'", UUID.class, payment);

        // Sealed: no entry may join it after its creating transaction, however well formed.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor)
                VALUES (?, ?, 'wallet:late', 'DEBIT', 1)
                """, UUID.randomUUID(), captureJournal))
                .as("a sealed journal takes no further entries")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("sealed after its creating transaction");

        // Immutable: entries cannot be edited or removed.
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1 WHERE journal_id = ?", captureJournal))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE journal_id = ?", captureJournal))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");

        // Singular: a payment keeps exactly one capture journal, whatever else it accumulates.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind)
                VALUES (?, ?, 'demo-merchant', 'CAD', 'CAPTURE')
                """, UUID.randomUUID(), payment))
                .as("exactly one capture journal per payment")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger_journals_one_capture_per_payment");

        // The original is exactly as the capture left it.
        assertThat(jdbc.queryForObject(
                "SELECT sum(amount_minor) FROM ledger_entries WHERE journal_id = ?", Long.class, captureJournal))
                .isEqualTo(10_000);
    }

    /**
     * Writes a return operation that has no journal, then one journal for it, in one transaction.
     *
     * <p>The journal-less return is the point. Attempting a wrong journal for a return the service has
     * already journalled hits the one-journal-per-return index first, so the amount and direction rules
     * are never reached and a test that claims to prove them proves uniqueness instead.
     *
     * <p>One transaction, because the deferred triggers are evaluated at commit: a rejection therefore
     * takes the return row with it and leaves the payment exactly as it was.
     */
    private void attemptReturnWithJournal(UUID payment, long returnAmount, long journalAmount,
                                          String debitAccount, String creditAccount) {
        transactions.executeWithoutResult(status -> {
            UUID returnId = UUID.randomUUID();
            Integer nextSequence = jdbc.queryForObject(
                    "SELECT coalesce(max(sequence_number), 0) + 1 FROM payment_returns WHERE payment_id = ?",
                    Integer.class, payment);
            jdbc.update("""
                    INSERT INTO payment_returns (id, payment_id, merchant_id, account_id, return_type,
                            amount_minor, currency, reason, sequence_number, created_at)
                    VALUES (?, ?, 'demo-merchant', ?, 'REFUND', ?, 'CAD', 'journal fixture', ?, now())
                    """, returnId, payment, accountId, returnAmount, nextSequence);
            jdbc.update("UPDATE payments SET returned_amount_minor = returned_amount_minor + ? WHERE id = ?",
                    returnAmount, payment);
            UUID journal = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind, source_return_id)
                    VALUES (?, ?, 'demo-merchant', 'CAD', 'RETURN', ?)
                    """, journal, payment, returnId);
            jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,'DEBIT',?)",
                    UUID.randomUUID(), journal, debitAccount, journalAmount);
            jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,'CREDIT',?)",
                    UUID.randomUUID(), journal, creditAccount, journalAmount);
        });
    }

    @Test
    void theDatabaseRefusesReturnsThatWouldOvershootTheCapture() throws Exception {
        UUID payment = captured(1_000);
        // Bypassing the service entirely: the row-level cap and the deferred budget trigger are the
        // backstop for a code path that never took the payment lock.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET returned_amount_minor = 1001 WHERE id = ?", payment))
                .as("a payment can never record more returned than captured")
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO payment_returns
                    (id, payment_id, merchant_id, account_id, return_type, amount_minor, currency, sequence_number, created_at)
                VALUES (?, ?, 'demo-merchant', ?, 'REFUND', 400, 'CAD', 1, now())
                """, UUID.randomUUID(), payment, accountId)))
                .as("a return row must agree with the payment's recorded total")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theReturnedTotalEqualityIsCheckedWhenAReturnIsWrittenAndNotOnEveryUpdate() throws Exception {
        UUID payment = captured(5_000);
        refund(payment, 1_000, null, key(), DEMO);

        // What the trigger does enforce: writing a return whose amount disagrees with the payment's
        // recorded total is refused, so the two cannot be made to disagree by adding a return.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO payment_returns
                    (id, payment_id, merchant_id, account_id, return_type, amount_minor, currency, sequence_number, created_at)
                VALUES (?, ?, 'demo-merchant', ?, 'REFUND', 400, 'CAD', 9, now())
                """, UUID.randomUUID(), payment, accountId)))
                .as("a return must leave the payment's total equal to the sum of its returns")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("return operations total");

        // What it does not: the constraint trigger fires on inserting a return, so an update that
        // touches only the payment is not checked against the return rows at all. The row-level cap
        // still applies, so the total cannot exceed the capture - but within that cap it can be made
        // to disagree with the operations it is supposed to summarise.
        //
        // This is the narrower guarantee the documentation now states. It is deliberately not widened
        // by a trigger on payments: reconciliation is the layer that catches records disagreeing with
        // each other, and a database that made this particular disagreement impossible would also make
        // that detection impossible to demonstrate. See docs/adr/0007.
        jdbc.update("UPDATE payments SET returned_amount_minor = 900 WHERE id = ?", payment);
        assertThat(recordedReturnedTotal(payment)).isEqualTo(900);
        assertThat(returnedTotal(payment)).as("the operations are unchanged and now disagree").isEqualTo(1_000);

        // Above the capture is still refused, whatever writes it.
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET returned_amount_minor = 5001 WHERE id = ?", payment))
                .isInstanceOf(DataAccessException.class);

        // Put it back so this test leaves its own payment reconciling.
        jdbc.update("UPDATE payments SET returned_amount_minor = 1000 WHERE id = ?", payment);
    }

    // ----- events -----

    @Test
    void eachReturnGetsItsOwnOrderedEventNamingTheOperation() throws Exception {
        UUID payment = captured(5_000);
        Reply first = refund(payment, 1_000, "first", key(), DEMO);
        Reply second = refund(payment, 1_000, "second", key(), DEMO);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_sequence, payload::text AS payload FROM outbox_events
                WHERE aggregate_id = ? ORDER BY aggregate_sequence
                """, payment);
        assertThat(events).hasSize(4);
        assertThat(events.stream().map(row -> row.get("event_type")))
                .containsExactly("payment.authorized.v1", "payment.captured.v1",
                        "payment.refunded.v1", "payment.refunded.v1");

        JsonNode thirdPayload = json.readTree((String) events.get(2).get("payload"));
        JsonNode fourthPayload = json.readTree((String) events.get(3).get("payload"));
        // Two partial refunds leave the payment's status alone, so the return block is the only thing
        // that distinguishes them.
        assertThat(thirdPayload.path("payment").path("status").asText()).isEqualTo("CAPTURED");
        assertThat(thirdPayload.path("returnOperation").path("id").asText())
                .isEqualTo(first.body().path("returnId").asText());
        assertThat(fourthPayload.path("returnOperation").path("id").asText())
                .isEqualTo(second.body().path("returnId").asText());
        assertThat(thirdPayload.path("payment").path("returnedAmountMinor").asLong()).isEqualTo(1_000);
        assertThat(fourthPayload.path("payment").path("returnedAmountMinor").asLong()).isEqualTo(2_000);
        assertThat(thirdPayload.path("payment").path("capturedAmountMinor").asLong()).isEqualTo(5_000);

        // A lifecycle event still carries no return block at all.
        assertThat(json.readTree((String) events.get(1).get("payload")).path("returnOperation").isNull()).isTrue();

        // And the audit trail records each accepted command separately.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE payment_id = ? AND action = 'payment.refunded.v1'",
                Long.class, payment)).isEqualTo(2);
    }

    // ----- helpers -----

    private List<Reply> inParallel(int attempts, Callable<Reply> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return work.call();
                }));
            }
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) replies.add(future.get(60, TimeUnit.SECONDS));
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private void writeReturnJournal(UUID payment, UUID returnId, long amount, String debit, String credit) {
        writeReturnJournalFor(UUID.randomUUID(), payment, returnId, amount, debit, credit);
    }

    private void writeReturnJournalFor(UUID journalId, UUID payment, UUID returnId, long amount,
                                       String debit, String credit) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind, source_return_id)
                    VALUES (?, ?, 'demo-merchant', 'CAD', 'RETURN', ?)
                    """, journalId, payment, returnId);
            jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,'DEBIT',?)",
                    UUID.randomUUID(), journalId, debit, amount);
            jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,'CREDIT',?)",
                    UUID.randomUUID(), journalId, credit, amount);
        });
    }

    private UUID newAccount(String merchant, long openingBalance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                id, merchant, openingBalance, openingBalance);
        return id;
    }

    private UUID authorized(long amount) throws Exception {
        return authorize(accountId, amount, key(), DEMO).id();
    }

    private UUID captured(long amount) throws Exception {
        return capturedFor("demo-merchant", accountId, amount);
    }

    private UUID capturedFor(String merchant, UUID account, long amount) throws Exception {
        String auth = merchant.equals("demo-merchant") ? DEMO : OTHER;
        UUID payment = authorize(account, amount, key(), auth).id();
        Reply capture = command("/v1/payments/" + payment + "/capture", null, key(), auth);
        assertThat(capture.status()).isEqualTo(200);
        return payment;
    }

    private Reply authorize(UUID account, long amount, String key, String auth) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account);
        body.put("amountMinor", amount);
        body.put("currency", "CAD");
        body.put("country", "CA");
        return command("/v1/payments/authorizations", body, key, auth);
    }

    private Reply refund(UUID payment, long amount, String reason, String key, String auth) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountMinor", amount);
        if (reason != null) body.put("reason", reason);
        return command("/v1/payments/" + payment + "/refunds", body, key, auth);
    }

    private Reply reverse(UUID payment, String reason, String key, String auth) throws Exception {
        Map<String, Object> body = reason == null ? Map.of() : Map.of("reason", reason);
        return command("/v1/payments/" + payment + "/reversal", body, key, auth);
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

    private Reply reply(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(), body.isBlank() ? json.nullNode() : json.readTree(body));
    }

    private void assertFunds(UUID account, long balance, long held) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT balance_minor, held_minor FROM accounts WHERE id = ?", account);
        assertThat(row).containsEntry("balance_minor", balance).containsEntry("held_minor", held);
    }

    private String statusOf(UUID payment) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment);
    }

    private long returnCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_returns WHERE payment_id = ?", Long.class, payment);
    }

    private long returnedTotal(UUID payment) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM payment_returns WHERE payment_id = ?",
                Long.class, payment);
    }

    private long recordedReturnedTotal(UUID payment) {
        return jdbc.queryForObject("SELECT returned_amount_minor FROM payments WHERE id = ?", Long.class, payment);
    }

    private long returnJournalCount(UUID payment) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journals WHERE payment_id = ? AND journal_kind = 'RETURN'",
                Long.class, payment);
    }

    private long journalCount() {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_journals", Long.class);
    }

    private long outboxCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, payment);
    }

    private Map<String, Object> captureJournalRow(UUID payment) {
        return jdbc.queryForMap("""
                SELECT j.id, j.currency, j.created_transaction_id,
                       (SELECT count(*) FROM ledger_entries e WHERE e.journal_id = j.id) AS entries,
                       (SELECT sum(e.amount_minor) FROM ledger_entries e WHERE e.journal_id = j.id) AS total
                FROM ledger_journals j WHERE j.payment_id = ? AND j.journal_kind = 'CAPTURE'
                """, payment);
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
