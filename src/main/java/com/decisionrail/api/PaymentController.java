package com.decisionrail.api;

import com.decisionrail.payments.*;
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
    public PaymentController(PaymentService payments) { this.payments = payments; }

    @PostMapping("/payments/authorizations")
    public ResponseEntity<PaymentView> authorize(Principal principal,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AuthorizationRequest request) {
        CommandResult result = payments.authorize(principal.getName(), key,
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

    @GetMapping("/payments/{id}")
    public PaymentView payment(Principal principal, @PathVariable UUID id) { return payments.payment(principal.getName(), id); }

    @GetMapping("/accounts/{id}")
    public AccountView account(Principal principal, @PathVariable UUID id) { return payments.account(principal.getName(), id); }

    @GetMapping("/payments/{id}/ledger")
    public List<LedgerEntryView> ledger(Principal principal, @PathVariable UUID id) { return payments.ledger(principal.getName(), id); }

    private static ResponseEntity<PaymentView> response(CommandResult result) {
        return ResponseEntity.status(result.httpStatus())
                .location(URI.create("/v1/payments/" + result.payment().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.payment());
    }

    private static void requireEmpty(String body) {
        if (body != null && !body.isBlank()) throw new PaymentException("UNEXPECTED_BODY", 400, "Capture and void do not accept a request body.");
    }
}
