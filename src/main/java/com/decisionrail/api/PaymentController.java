package com.decisionrail.api;

import com.decisionrail.payments.*;
import com.decisionrail.reconciliation.ReconciliationReport;
import com.decisionrail.reconciliation.ReconciliationRequest;
import com.decisionrail.reconciliation.ReconciliationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class PaymentController {
    private final PaymentService payments;
    private final ReconciliationService reconciliation;

    public PaymentController(PaymentService payments, ReconciliationService reconciliation) {
        this.payments = payments;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/payments/authorizations")
    public ResponseEntity<PaymentView> authorize(Principal principal,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AuthorizationRequest request) {
        CommandResult<PaymentView> result = payments.authorize(principal.getName(), key,
                new AuthorizationCommand(request.accountId(), request.amountMinor(), request.currency(), request.country()));
        return response(result);
    }

    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<PaymentView> capture(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        requireEmpty(body);
        return response(payments.capture(principal.getName(), key, id));
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<PaymentView> voidPayment(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        requireEmpty(body);
        return response(payments.voidPayment(principal.getName(), key, id));
    }

    /**
     * Returns captured money. Partial by amount, or the whole remaining eligible amount.
     *
     * <p>201 on a new return, and the same 201 with {@code Idempotency-Replayed: true} when the key
     * has already been used for this exact request: a replay is the original receipt, not a new one.
     */
    @PostMapping("/payments/{id}/refunds")
    public ResponseEntity<ReturnReceiptView> refund(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReturnRequest request) {
        return returnResponse(payments.returnFunds(principal.getName(), key,
                new ReturnCommand(id, ReturnType.REFUND, request.amountMinor(), request.reason())));
    }

    /**
     * Reverses a capture in full.
     *
     * <p>Refused once anything has been returned; the remainder is a refund, not a reversal. This is
     * not an authorization reversal - releasing a hold before capture is {@code POST .../void}, which
     * moves no money and writes no journal.
     */
    @PostMapping("/payments/{id}/reversal")
    public ResponseEntity<ReturnReceiptView> reverse(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) @Valid ReturnRequest request) {
        String reason = request == null ? null : request.reason();
        if (request != null && request.amountMinor() != null) {
            throw new PaymentException("INVALID_RETURN_INPUT", 400,
                    "A reversal returns the whole captured amount and must not state one.");
        }
        return returnResponse(payments.returnFunds(principal.getName(), key,
                new ReturnCommand(id, ReturnType.REVERSAL, null, reason)));
    }

    /** What was captured, what has been returned, what is left, and every return operation so far. */
    @GetMapping("/payments/{id}/returns")
    public PaymentReturnsView returns(Principal principal, @PathVariable UUID id) {
        return payments.returns(principal.getName(), id);
    }

    /**
     * The merchant's own reconciliation report: read-only, and scoped to the authenticated merchant.
     *
     * <p>There is no merchantId parameter here. Reconciling another tenant is a separate route under
     * {@code /v1/ops}, restricted to ADMIN, so cross-tenant access cannot be reached by adding a query
     * parameter to this one.
     */
    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation(Principal principal,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Integer accountLimit,
            @RequestParam(required = false) Integer paymentLimit) {
        return reconciliation.forMerchant(principal.getName(),
                ReconciliationRequest.parse(accountId, accountLimit, paymentLimit));
    }

    @GetMapping("/payments/{id}")
    public PaymentView payment(Principal principal, @PathVariable UUID id) { return payments.payment(principal.getName(), id); }

    @GetMapping("/accounts/{id}")
    public AccountView account(Principal principal, @PathVariable UUID id) { return payments.account(principal.getName(), id); }

    @GetMapping("/payments/{id}/ledger")
    public List<LedgerEntryView> ledger(Principal principal, @PathVariable UUID id) { return payments.ledger(principal.getName(), id); }

    private static ResponseEntity<PaymentView> response(CommandResult<PaymentView> result) {
        return ResponseEntity.status(result.httpStatus())
                .location(URI.create("/v1/payments/" + result.body().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.body());
    }

    private static ResponseEntity<ReturnReceiptView> returnResponse(CommandResult<ReturnReceiptView> result) {
        return ResponseEntity.status(result.httpStatus())
                .location(URI.create("/v1/payments/" + result.body().paymentId() + "/returns"))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.body());
    }

    private static void requireEmpty(String body) {
        if (body != null && !body.isBlank()) throw new PaymentException("UNEXPECTED_BODY", 400, "Capture and void do not accept a request body.");
    }
}
