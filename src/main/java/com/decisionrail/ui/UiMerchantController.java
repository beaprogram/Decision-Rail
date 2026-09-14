package com.decisionrail.ui;

import com.decisionrail.payments.AccountView;
import com.decisionrail.payments.AuthorizationCommand;
import com.decisionrail.payments.CommandResult;
import com.decisionrail.payments.LedgerEntryView;
import com.decisionrail.payments.PaymentReadService;
import com.decisionrail.payments.PaymentSearchPage;
import com.decisionrail.payments.PaymentSearchQuery;
import com.decisionrail.payments.PaymentService;
import com.decisionrail.payments.PaymentTimelineView;
import com.decisionrail.payments.PaymentReturnsView;
import com.decisionrail.payments.PaymentView;
import com.decisionrail.payments.ReturnCommand;
import com.decisionrail.payments.ReturnReceiptView;
import com.decisionrail.payments.ReturnType;
import com.decisionrail.payments.PaymentException;
import com.decisionrail.api.AuthorizationRequest;
import com.decisionrail.api.ReturnRequest;
import com.decisionrail.reconciliation.ReconciliationReport;
import com.decisionrail.reconciliation.ReconciliationRequest;
import com.decisionrail.reconciliation.ReconciliationService;
import com.decisionrail.replay.ReplayJobView;
import com.decisionrail.replay.ReplayReport;
import com.decisionrail.replay.ReplayResultView;
import com.decisionrail.replay.ReplayService;
import com.decisionrail.shadow.ShadowComparisonView;
import com.decisionrail.shadow.ShadowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The merchant half of the browser API.
 *
 * <p>Every method here delegates to the same services the {@code /v1} API uses. There is no second
 * implementation of a payment command, no alternative transaction boundary, and no query that resolves
 * ownership differently: the merchant always comes from {@code Principal}, never from the request.
 *
 * <p>Mutations still require an {@code Idempotency-Key}. The dashboard generates one key per logical
 * command and reuses it across retries, so a retried authorization returns the original result instead
 * of reserving funds twice.
 */
@RestController
@RequestMapping("/ui")
public class UiMerchantController {
    private final PaymentService payments;
    private final PaymentReadService reads;
    private final ReplayService replay;
    private final ShadowService shadow;
    private final ReconciliationService reconciliation;

    public UiMerchantController(PaymentService payments, PaymentReadService reads,
                               ReplayService replay, ShadowService shadow,
                               ReconciliationService reconciliation) {
        this.payments = payments;
        this.reads = reads;
        this.replay = replay;
        this.shadow = shadow;
        this.reconciliation = reconciliation;
    }

    // ----- accounts and payments -----

    /** Every account the signed-in merchant owns, so the browser never has to be told which it may use. */
    @GetMapping("/accounts")
    public List<AccountView> accounts(Principal principal) {
        return reads.accounts(principal.getName());
    }

    @GetMapping("/accounts/{id}")
    public AccountView account(Principal principal, @PathVariable UUID id) {
        return payments.account(principal.getName(), id);
    }

    /**
     * Authoritative payment search.
     *
     * <p>Reads the payments table, so a payment appears here as soon as its transaction commits, and
     * keeps appearing while the broker is down. Ordering is {@code created_at DESC, id DESC} and paging
     * is by opaque cursor, so a payment created mid-paging cannot shift a page boundary and hide a row.
     */
    @GetMapping("/payments")
    public PaymentSearchPage search(Principal principal,
            @RequestParam(required = false) String paymentId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String riskOutcome,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return reads.search(principal.getName(), PaymentSearchQuery.parse(
                paymentId, accountId, status, riskOutcome, currency, createdFrom, createdTo, limit, cursor));
    }

    @GetMapping("/payments/{id}")
    public PaymentView payment(Principal principal, @PathVariable UUID id) {
        return payments.payment(principal.getName(), id);
    }

    @GetMapping("/payments/{id}/ledger")
    public List<LedgerEntryView> ledger(Principal principal, @PathVariable UUID id) {
        return payments.ledger(principal.getName(), id);
    }

    /** Lifecycle evidence: commands, per-event delivery state, and per-consumer-group projection. */
    @GetMapping("/payments/{id}/timeline")
    public PaymentTimelineView timeline(Principal principal, @PathVariable UUID id) {
        return reads.timeline(principal.getName(), id);
    }

    @GetMapping("/payments/{id}/shadow")
    public List<ShadowComparisonView> paymentShadow(Principal principal, @PathVariable UUID id) {
        return shadow.comparisons(principal.getName(), id);
    }

    @GetMapping("/shadow-comparisons")
    public List<ShadowComparisonView> shadowComparisons(Principal principal,
            @RequestParam(defaultValue = "false") boolean divergedOnly,
            @RequestParam(defaultValue = "25") int limit) {
        return shadow.recent(principal.getName(), divergedOnly, limit);
    }

    // ----- payment commands -----

    @PostMapping("/payments/authorizations")
    public ResponseEntity<PaymentView> authorize(Principal principal,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AuthorizationRequest request) {
        return command(payments.authorize(principal.getName(), key, new AuthorizationCommand(
                request.accountId(), request.amountMinor(), request.currency(), request.country())));
    }

    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<PaymentView> capture(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return command(payments.capture(principal.getName(), key, id));
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<PaymentView> voidPayment(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return command(payments.voidPayment(principal.getName(), key, id));
    }

    // ----- returns -----

    /** What was captured, what has been returned, what is left, and every return operation so far. */
    @GetMapping("/payments/{id}/returns")
    public PaymentReturnsView returns(Principal principal, @PathVariable UUID id) {
        return payments.returns(principal.getName(), id);
    }

    @PostMapping("/payments/{id}/refunds")
    public ResponseEntity<ReturnReceiptView> refund(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReturnRequest request) {
        return returnCommand(payments.returnFunds(principal.getName(), key,
                new ReturnCommand(id, ReturnType.REFUND, request.amountMinor(), request.reason())));
    }

    @PostMapping("/payments/{id}/reversal")
    public ResponseEntity<ReturnReceiptView> reverse(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) @Valid ReturnRequest request) {
        if (request != null && request.amountMinor() != null) {
            throw new PaymentException("INVALID_RETURN_INPUT", 400,
                    "A reversal returns the whole captured amount and must not state one.");
        }
        return returnCommand(payments.returnFunds(principal.getName(), key,
                new ReturnCommand(id, ReturnType.REVERSAL, null, request == null ? null : request.reason())));
    }

    // ----- reconciliation -----

    /**
     * The merchant's own reconciliation report.
     *
     * <p>Read-only and merchant-scoped. It derives what the balances ought to be from the ledger and
     * the operations that wrote it, and reports where they disagree; it never repairs anything.
     */
    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation(Principal principal,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Integer accountLimit,
            @RequestParam(required = false) Integer paymentLimit) {
        return reconciliation.forMerchant(principal.getName(),
                ReconciliationRequest.parse(accountId, accountLimit, paymentLimit));
    }

    // ----- replay -----

    @PostMapping("/replay-jobs")
    public ResponseEntity<ReplayJobView> createReplayJob(Principal principal,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateReplayJobRequest request) {
        ReplayService.CreatedJob created = replay.create(principal.getName(), key,
                new ReplayService.CreateCommand(request.candidateVersion(), request.limit(), request.from()));
        return ResponseEntity.status(created.replayed() ? 200 : 201)
                .header("Idempotency-Replayed", Boolean.toString(created.replayed()))
                .body(created.job());
    }

    @GetMapping("/replay-jobs")
    public List<ReplayJobView> replayJobs(Principal principal, @RequestParam(defaultValue = "25") int limit) {
        return replay.jobs(principal.getName(), limit);
    }

    @GetMapping("/replay-jobs/{id}")
    public ReplayJobView replayJob(Principal principal, @PathVariable UUID id) {
        return replay.job(principal.getName(), id);
    }

    @GetMapping("/replay-jobs/{id}/report")
    public ReplayReport replayReport(Principal principal, @PathVariable UUID id) {
        return replay.report(principal.getName(), id);
    }

    @GetMapping("/replay-jobs/{id}/results")
    public List<ReplayResultView> replayResults(Principal principal, @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean divergedOnly,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return replay.results(principal.getName(), id, divergedOnly, limit, offset);
    }

    private static ResponseEntity<ReturnReceiptView> returnCommand(CommandResult<ReturnReceiptView> result) {
        return ResponseEntity.status(result.httpStatus())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.body());
    }

    private static ResponseEntity<PaymentView> command(CommandResult<PaymentView> result) {
        return ResponseEntity.status(result.httpStatus())
                // Tells the dashboard this response came from the durable idempotency record, which
                // matters because a replayed authorization carries the snapshot from when it was first
                // made and may be older than the payment's current state.
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.body());
    }

    public record CreateReplayJobRequest(
            @NotNull @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}") String candidateVersion,
            @Min(1) @Max(5000) Integer limit,
            Instant from) {}
}
