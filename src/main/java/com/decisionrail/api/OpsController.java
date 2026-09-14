package com.decisionrail.api;

import com.decisionrail.events.OutboxBacklog;
import com.decisionrail.reconciliation.ReconciliationReport;
import com.decisionrail.reconciliation.ReconciliationRequest;
import com.decisionrail.reconciliation.ReconciliationService;
import com.decisionrail.events.OutboxDispatcher;
import com.decisionrail.shadow.ShadowService;
import com.decisionrail.shadow.ShadowSettingsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative operations. Reachable only by the ADMIN identity; the metrics-only
 * OPERATIONS account cannot call any of these.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsController {
    private static final int MAX_REDRIVE = 500;

    private final OutboxDispatcher dispatcher;
    private final ShadowService shadow;
    private final ReconciliationService reconciliation;

    public OpsController(OutboxDispatcher dispatcher, ShadowService shadow,
                         ReconciliationService reconciliation) {
        this.dispatcher = dispatcher;
        this.shadow = shadow;
        this.reconciliation = reconciliation;
    }

    /**
     * Reconciliation for any merchant, which is why it lives here rather than beside the merchant API.
     *
     * <p>{@code /v1/ops/**} is restricted to ADMIN by the security configuration, listed ahead of the
     * broad merchant rule so it cannot fall through and be authorised as an ordinary merchant call.
     * The merchant is read from the query, and the service method that accepts one is separate from
     * the one merchants use.
     */
    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation(
            @RequestParam String merchantId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Integer accountLimit,
            @RequestParam(required = false) Integer paymentLimit) {
        return reconciliation.forNamedMerchant(merchantId,
                ReconciliationRequest.parse(accountId, accountLimit, paymentLimit));
    }

    /** Durable backlog, terminal failures, blocked payment streams, and breaker state. */
    @GetMapping("/outbox/backlog")
    public OutboxBacklog backlog() {
        return dispatcher.backlog();
    }

    /**
     * Returns terminally failed events to the pending pool.
     *
     * <p>Retry-safe without an idempotency key: the statement only matches rows that are
     * still FAILED, so repeating the same redrive request is a no-op rather than a second
     * effect. Event identity and payload are never rewritten, so a redriven event is
     * deduplicated by consumers exactly like any other redelivery.
     *
     * <p>Redrive cannot skip ahead. A payment's later event stays unclaimable until its
     * earlier event is published, so {@code stillBlockedPaymentCount} tells the operator
     * whether anything remains stalled after this call.
     */
    @PostMapping("/outbox/redrive")
    public RedriveResponse redrive(@Valid @RequestBody RedriveRequest request) {
        int limit = request.limit() == null ? MAX_REDRIVE : request.limit();
        List<UUID> redriven = dispatcher.redrive(request.merchantId(), request.paymentId(), request.eventIds(), limit);
        OutboxBacklog after = dispatcher.backlog();
        return new RedriveResponse(redriven.size(), redriven, after.blockedPaymentCount());
    }

    /**
     * @param merchantId optional tenant filter
     * @param paymentId  optional payment filter; redrives every failed event for that payment
     *                   so an earlier event is never left behind
     * @param eventIds   optional explicit selection, at most 500 entries
     */
    public record RedriveRequest(
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String merchantId,
            UUID paymentId,
            @Size(max = MAX_REDRIVE) List<UUID> eventIds,
            @Min(1) @Max(MAX_REDRIVE) Integer limit) {}

    public record RedriveResponse(int redrivenCount, List<UUID> redrivenEventIds, long stillBlockedPaymentCount) {}

    /** Current shadow configuration, queue depth, and comparison totals. */
    @GetMapping("/shadow")
    public ShadowSettingsView shadowSettings() {
        return shadow.settings();
    }

    /**
     * Enables or disables shadow evaluation of a candidate policy.
     *
     * <p>Idempotent, so a retry needs no key. Enabling a candidate grants it no authority over real
     * payments: it selects a policy to evaluate alongside live decisions and nothing more.
     */
    @PutMapping("/shadow")
    public ShadowSettingsView configureShadow(Principal principal, @Valid @RequestBody ShadowRequest request) {
        return shadow.configure(request.enabled(), request.candidateVersion(), principal.getName());
    }

    public record ShadowRequest(
            boolean enabled,
            @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}") String candidateVersion) {}
}
