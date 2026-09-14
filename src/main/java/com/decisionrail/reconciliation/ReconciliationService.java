package com.decisionrail.reconciliation;

import com.decisionrail.payments.PaymentException;
import com.decisionrail.reconciliation.ReconciliationFinding.Reference;
import com.decisionrail.reconciliation.ReconciliationFinding.Severity;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks that recorded money agrees with the evidence for it, and reports where it does not.
 *
 * <h2>Read-only, and structurally so</h2>
 * Nothing here writes. It cannot repair a balance, rewrite a journal, or create a return: a detected
 * discrepancy becomes a finding an operator acts on, never a financial mutation this report performs
 * on its own authority. Automatically "correcting" a balance would destroy the only evidence that
 * something went wrong, and would do it using the same code whose output is in doubt.
 *
 * <h2>One snapshot</h2>
 * The whole report runs in a single REPEATABLE READ transaction, so every number in it comes from one
 * consistent moment. Without that, a refund committing between two queries would make a correct system
 * look broken - the balance read after it, the journals read before it - and the report would generate
 * its own false findings under ordinary concurrent load.
 *
 * <h2>What it deliberately does not read</h2>
 * The activity projection. That read model is built from delivered events and is allowed to lag, so
 * comparing it against the authoritative tables would report delivery latency as missing money. Every
 * figure below comes from {@code accounts}, {@code payments}, {@code payment_returns} and the ledger.
 */
@Service
public class ReconciliationService {
    private static final List<String> CHECKS = List.of(
            "ACCOUNT_BALANCE: opening balance, less capture debits against the wallet, plus return credits to it",
            "ACCOUNT_HELD: held funds against the payments still authorized on the account",
            "LEDGER_DIRECTION: wallet entries sitting on the wrong side for their journal's kind",
            "CAPTURE_JOURNAL: exactly one per captured payment, for the amount the capture recorded",
            "RETURN_TOTAL: the payment's returned total against its return operations and their journals",
            "RETURN_JOURNAL: one balanced journal per return, for that return's amount",
            "RETURN_BUDGET: returned never above captured",
            "OWNERSHIP: every journal and return carrying the payment's own merchant, currency and account");

    private static final List<String> LIMITATIONS = List.of(
            "Read-only. Nothing here repairs a balance, writes a journal, or creates a return.",
            "All of this evidence lives in one database. A single mistaken transaction that wrote the "
                    + "same wrong amount to the payment, its journal and the balance would reconcile "
                    + "perfectly; what this detects is records disagreeing with each other.",
            "The activity projection is not read. A payment whose events have not been delivered yet is "
                    + "delivery lag, not missing money, and is out of scope here by design.",
            "Balances and holds are derived over an account's whole history; per-payment and per-return "
                    + "checks stop at the requested limit, and the report is marked incomplete when they do.",
            "Currencies are never combined. Each account holds one currency and is reconciled in it.");

    private final ReconciliationStore store;
    private final Clock clock;

    public ReconciliationService(ReconciliationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * The report for one merchant.
     *
     * <p>The merchant is the one resolved from authentication by the caller, and it is carried into
     * every query rather than used to filter results afterwards. There is no cross-merchant form of
     * this method; a broader view is a separate, separately authorised entry point.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public ReconciliationReport forMerchant(String merchant, ReconciliationRequest request) {
        if (merchant == null || merchant.isBlank()) {
            throw new PaymentException("ACCESS_DENIED", 403, "A merchant identity is required.");
        }
        List<ReconciliationStore.AccountRow> accounts =
                store.accounts(merchant, request.accountId(), request.accountLimit() + 1);
        boolean accountsTruncated = accounts.size() > request.accountLimit();
        if (accountsTruncated) accounts = accounts.subList(0, request.accountLimit());

        List<ReconciliationStore.PaymentRow> payments =
                store.payments(merchant, request.accountId(), request.paymentLimit() + 1);
        boolean paymentsTruncated = payments.size() > request.paymentLimit();
        if (paymentsTruncated) payments = payments.subList(0, request.paymentLimit());

        List<ReconciliationStore.ReturnRow> returns =
                store.returns(merchant, request.accountId(), request.paymentLimit() + 1);
        boolean returnsTruncated = returns.size() > request.paymentLimit();
        if (returnsTruncated) returns = returns.subList(0, request.paymentLimit());

        List<ReconciliationFinding> findings = new ArrayList<>();
        Set<String> currencies = new LinkedHashSet<>();
        for (ReconciliationStore.AccountRow account : accounts) {
            currencies.add(account.currency());
            checkAccount(account, findings);
        }
        for (ReconciliationStore.PaymentRow payment : payments) {
            checkPayment(payment, findings);
        }
        for (ReconciliationStore.ReturnRow returned : returns) {
            checkReturn(returned, findings);
        }

        boolean complete = !accountsTruncated && !paymentsTruncated && !returnsTruncated;
        String incompleteReason = complete ? null : truncationReason(accountsTruncated, paymentsTruncated,
                returnsTruncated, request);
        ReconciliationReport.Scope scope = new ReconciliationReport.Scope(request.accountId(),
                request.accountLimit(), request.paymentLimit(), accounts.size(), payments.size(),
                returns.size(), List.copyOf(currencies), "REPEATABLE READ, one snapshot for the whole report",
                complete, incompleteReason, CHECKS);
        return new ReconciliationReport(merchant, clock.instant().truncatedTo(ChronoUnit.MILLIS),
                ReconciliationReport.statusFor(complete, !findings.isEmpty()), scope,
                List.copyOf(findings), LIMITATIONS);
    }


    /**
     * The same report for a merchant the caller names, rather than the one they are.
     *
     * <p>A deliberately separate entry point. The merchant-facing method takes its identity from
     * authentication and has no way to name another tenant; this one accepts a merchant id and is
     * reachable only from routes the security configuration restricts to ADMIN. Sharing one method
     * with an optional override would make the privileged case a parameter, and a parameter is exactly
     * what an authorisation mistake turns into cross-tenant access.
     *
     * <p>OPERATIONS cannot reach this. That identity stays metrics-only however operator-shaped the
     * product becomes.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public ReconciliationReport forNamedMerchant(String merchantId, ReconciliationRequest request) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new PaymentException("INVALID_RECONCILIATION_INPUT", 400, "merchantId is required.");
        }
        return forMerchant(merchantId.strip(), request);
    }

    /**
     * An account's balance against the ledger, and its holds against the authorizations that placed
     * them.
     *
     * <p>The expected balance is rebuilt from the entries naming this wallet: the opening balance, less
     * what captures debited, plus what returns credited back. It is not compared against the payments'
     * status columns, which the same transactions wrote; the journals are the separate record.
     */
    private static void checkAccount(ReconciliationStore.AccountRow account, List<ReconciliationFinding> findings) {
        long expectedBalance = account.openingBalanceMinor() - account.capturedDebits() + account.returnedCredits();
        if (expectedBalance != account.balanceMinor()) {
            findings.add(ReconciliationFinding.of("ACCOUNT_BALANCE_MISMATCH", Severity.CRITICAL,
                    "ACCOUNT", account.id(), account.currency(), expectedBalance, account.balanceMinor(),
                    "Opening balance %d less capture debits %d plus return credits %d does not equal the recorded balance."
                            .formatted(account.openingBalanceMinor(), account.capturedDebits(), account.returnedCredits()),
                    List.of(new Reference("ACCOUNT", account.id()))));
        }
        if (account.outstandingAuthorizations() != account.heldMinor()) {
            findings.add(ReconciliationFinding.of("ACCOUNT_HELD_MISMATCH", Severity.CRITICAL,
                    "ACCOUNT", account.id(), account.currency(), account.outstandingAuthorizations(),
                    account.heldMinor(),
                    "Held funds do not equal the total of payments still authorized on this account.",
                    List.of(new Reference("ACCOUNT", account.id()))));
        }
        if (account.reversedDirectionEntries() > 0) {
            // A balanced journal moving value the wrong way passes every arithmetic check there is.
            findings.add(ReconciliationFinding.of("LEDGER_ENTRY_DIRECTION_WRONG", Severity.CRITICAL,
                    "ACCOUNT", account.id(), account.currency(), 0L, account.reversedDirectionEntries(),
                    "Wallet entries are on the wrong side for their journal's kind: a capture must debit "
                            + "the wallet and a return must credit it.",
                    List.of(new Reference("ACCOUNT", account.id()))));
        }
    }

    private static void checkPayment(ReconciliationStore.PaymentRow payment, List<ReconciliationFinding> findings) {
        Reference self = new Reference("PAYMENT", payment.id());
        boolean captured = "CAPTURED".equals(payment.status());

        if (captured) {
            long expectedCapture = payment.capturedAmountMinor() == null ? 0L : payment.capturedAmountMinor();
            if (payment.captureJournals() != 1) {
                findings.add(ReconciliationFinding.of("CAPTURE_JOURNAL_COUNT_WRONG", Severity.CRITICAL,
                        "PAYMENT", payment.id(), payment.currency(), 1L, payment.captureJournals(),
                        "A captured payment must have exactly one capture journal.", List.of(self)));
            }
            if (payment.captureDebitTotal() != expectedCapture) {
                findings.add(ReconciliationFinding.of("CAPTURE_JOURNAL_AMOUNT_MISMATCH", Severity.CRITICAL,
                        "PAYMENT", payment.id(), payment.currency(), expectedCapture, payment.captureDebitTotal(),
                        "The capture journal does not debit the amount the capture recorded.", List.of(self)));
            }
        } else if (payment.captureJournals() > 0) {
            findings.add(ReconciliationFinding.of("JOURNAL_ON_UNCAPTURED_PAYMENT", Severity.CRITICAL,
                    "PAYMENT", payment.id(), payment.currency(), 0L, payment.captureJournals(),
                    "A payment that was not captured has a capture journal.", List.of(self)));
        }

        // Three records of the same money, checked against each other rather than against one of
        // themselves: the payment's total, the return operations, and the journals those produced.
        if (payment.returnedAmountMinor() != payment.returnOperationsTotal()) {
            findings.add(ReconciliationFinding.of("RETURN_TOTAL_MISMATCH", Severity.CRITICAL,
                    "PAYMENT", payment.id(), payment.currency(), payment.returnOperationsTotal(),
                    payment.returnedAmountMinor(),
                    "The payment's returned total does not equal the sum of its %d return operations."
                            .formatted(payment.returnOperations()), List.of(self)));
        }
        if (payment.returnOperationsTotal() != payment.returnJournalTotal()) {
            findings.add(ReconciliationFinding.of("RETURN_JOURNAL_TOTAL_MISMATCH", Severity.CRITICAL,
                    "PAYMENT", payment.id(), payment.currency(), payment.returnOperationsTotal(),
                    payment.returnJournalTotal(),
                    "The return journals do not credit what the return operations say was returned.",
                    List.of(self)));
        }
        if (payment.returnOperations() != payment.returnJournals()) {
            findings.add(ReconciliationFinding.of("RETURN_JOURNAL_COUNT_WRONG", Severity.CRITICAL,
                    "PAYMENT", payment.id(), payment.currency(), payment.returnOperations(),
                    payment.returnJournals(), "Every return operation must have exactly one journal.",
                    List.of(self)));
        }
        long capturedAmount = payment.capturedAmountMinor() == null ? 0L : payment.capturedAmountMinor();
        if (payment.returnedAmountMinor() > capturedAmount) {
            findings.add(ReconciliationFinding.of("RETURNED_ABOVE_CAPTURED", Severity.CRITICAL,
                    "PAYMENT", payment.id(), payment.currency(), capturedAmount, payment.returnedAmountMinor(),
                    "More has been returned than was ever captured.", List.of(self)));
        }
        if (payment.journalOwnershipMismatches() > 0 || payment.returnOwnershipMismatches() > 0) {
            findings.add(ReconciliationFinding.of("OWNERSHIP_MISMATCH", Severity.CRITICAL,
                    "PAYMENT", payment.id(), payment.currency(), 0L,
                    payment.journalOwnershipMismatches() + payment.returnOwnershipMismatches(),
                    "A journal or return on this payment names a different merchant, currency or account.",
                    List.of(self)));
        }
    }

    private static void checkReturn(ReconciliationStore.ReturnRow returned, List<ReconciliationFinding> findings) {
        List<Reference> references = List.of(new Reference("RETURN", returned.id()),
                new Reference("PAYMENT", returned.paymentId()));
        if (returned.journalId() == null) {
            findings.add(ReconciliationFinding.of("RETURN_WITHOUT_JOURNAL", Severity.CRITICAL,
                    "RETURN", returned.id(), returned.currency(), returned.amountMinor(), null,
                    "This return credited an account with no journal recording it.", references));
            return;
        }
        List<Reference> withJournal = List.of(new Reference("RETURN", returned.id()),
                new Reference("PAYMENT", returned.paymentId()), new Reference("JOURNAL", returned.journalId()));
        if (returned.journalCreditTotal() != returned.amountMinor()) {
            findings.add(ReconciliationFinding.of("RETURN_JOURNAL_AMOUNT_MISMATCH", Severity.CRITICAL,
                    "RETURN", returned.id(), returned.currency(), returned.amountMinor(),
                    returned.journalCreditTotal(),
                    "The journal credits a different amount than this return operation returned.", withJournal));
        }
        if (returned.journalCreditTotal() != returned.journalDebitTotal()) {
            findings.add(ReconciliationFinding.of("RETURN_JOURNAL_UNBALANCED", Severity.CRITICAL,
                    "RETURN", returned.id(), returned.currency(), returned.journalDebitTotal(),
                    returned.journalCreditTotal(), "The return journal does not balance.", withJournal));
        }
    }

    private static String truncationReason(boolean accounts, boolean payments, boolean returns,
                                           ReconciliationRequest request) {
        List<String> reasons = new ArrayList<>();
        if (accounts) reasons.add("more than " + request.accountLimit() + " accounts match");
        if (payments) reasons.add("more than " + request.paymentLimit() + " payments match");
        if (returns) reasons.add("more than " + request.paymentLimit() + " return operations match");
        return String.join("; ", reasons)
                + ". Only the examined prefix is covered above; narrow the scope with accountId, or raise the limit.";
    }
}
