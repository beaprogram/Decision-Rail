package com.decisionrail.ui;

import com.decisionrail.events.FailedEventView;
import com.decisionrail.events.OutboxBacklog;
import com.decisionrail.events.OutboxDispatcher;
import com.decisionrail.reconciliation.ReconciliationReport;
import com.decisionrail.reconciliation.ReconciliationRequest;
import com.decisionrail.reconciliation.ReconciliationService;
import com.decisionrail.events.OutboxStore;
import com.decisionrail.payments.PaymentException;
import com.decisionrail.shadow.ShadowService;
import com.decisionrail.shadow.ShadowSettingsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administrative delivery workspace.
 *
 * <p>ADMIN only, and deliberately limited to inspecting and redriving durable state. There is no
 * endpoint here that stops a broker, edits a database row, or arms a fault hook: outages are produced
 * by the local test and demo tooling, not by a button in a browser.
 */
@RestController
@RequestMapping("/ui/ops")
public class UiOpsController {
    private static final int MAX_REDRIVE = 500;
    private static final int MAX_FAILED_PAGE = 100;

    private final OutboxDispatcher dispatcher;
    private final OutboxStore outbox;
    private final ShadowService shadow;
    private final HealthEndpoint health;
    private final ReconciliationService reconciliation;

    public UiOpsController(OutboxDispatcher dispatcher, OutboxStore outbox, ShadowService shadow, HealthEndpoint health,
                           ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
        this.dispatcher = dispatcher;
        this.outbox = outbox;
        this.shadow = shadow;
        this.health = health;
    }

    /**
     * One consolidated view of delivery health.
     *
     * <p>Liveness, readiness, and asynchronous capability stay three separate answers, because they mean
     * different things: readiness excludes the broker, so a broker outage degrades the asynchronous
     * signal while readiness correctly stays up and payment traffic keeps arriving.
     */
    /**
     * Reconciliation for any merchant, from the administrative workspace.
     *
     * <p>{@code /ui/ops/**} is restricted to ADMIN on the browser chain, separately from the stateless
     * one. The two chains enforce this independently and neither borrows the other's protection.
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

    @GetMapping("/delivery")
    public DeliveryStatus delivery() {
        OutboxBacklog backlog = dispatcher.backlog();
        return new DeliveryStatus(
                statusOf("liveness"), statusOf("readiness"), statusOf("async"),
                backlog.breakerState(), backlog.countsByStatus(), backlog.oldestPendingAt(),
                backlog.oldestPendingAgeSeconds(), backlog.blockedPaymentCount());
    }

    /**
     * Terminally failed events, oldest first, with enough detail to choose a redrive target.
     *
     * <p>Aggregate counts cannot be inspected or acted on; this is the list an operator actually needs.
     * {@code blocksLaterEvents} marks the failures that are holding a whole payment stream.
     */
    @GetMapping("/outbox/failed")
    public FailedEventPage failedEvents(@RequestParam(defaultValue = "25") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        int bounded = Math.clamp(limit, 1, MAX_FAILED_PAGE);
        int from = Math.max(0, offset);
        List<FailedEventView> events = outbox.failedEvents(bounded + 1, from);
        boolean more = events.size() > bounded;
        return new FailedEventPage(more ? events.subList(0, bounded) : events, from, bounded, more,
                outbox.countByStatus("FAILED"));
    }

    /**
     * Redrives an explicit selection.
     *
     * <p>Unlike the scripted API, this refuses a request with no target at all. In a browser an empty
     * filter set is almost always a mistake rather than an instruction to redrive everything that has
     * ever failed, and the cost of that mistake is a large burst of redelivery.
     *
     * <p>Event identity, payload, and per-payment ordering are untouched: a redriven event is
     * deduplicated by consumers exactly like any other redelivery, and a later event stays unclaimable
     * until its predecessor publishes.
     */
    @PostMapping("/outbox/redrive")
    public RedriveResult redrive(@Valid @RequestBody RedriveRequest request) {
        boolean hasEvents = request.eventIds() != null && !request.eventIds().isEmpty();
        if (!hasEvents && request.paymentId() == null) {
            throw new PaymentException("REDRIVE_TARGET_REQUIRED", 400,
                    "Select specific events or a payment to redrive. Redriving every failed event is not offered here.");
        }
        List<UUID> redriven = dispatcher.redrive(null, request.paymentId(),
                hasEvents ? request.eventIds() : null, MAX_REDRIVE);
        OutboxBacklog after = dispatcher.backlog();
        return new RedriveResult(redriven.size(), redriven, after.blockedPaymentCount(),
                after.countsByStatus().getOrDefault("FAILED", 0L));
    }

    @GetMapping("/shadow")
    public ShadowSettingsView shadowSettings() {
        return shadow.settings();
    }

    @PutMapping("/shadow")
    public ShadowSettingsView configureShadow(Principal principal, @Valid @RequestBody ShadowRequest request) {
        return shadow.configure(request.enabled(), request.candidateVersion(), principal.getName());
    }

    private String statusOf(String group) {
        HealthComponent component = health.healthForPath(group);
        return component == null ? "UNKNOWN" : component.getStatus().getCode();
    }

    /** Liveness, readiness, and asynchronous capability as distinct signals, plus backlog detail. */
    public record DeliveryStatus(
            String liveness,
            String readiness,
            String asyncDelivery,
            String breakerState,
            Map<String, Long> countsByStatus,
            java.time.Instant oldestPendingAt,
            long oldestPendingAgeSeconds,
            long blockedPaymentCount) {}

    public record FailedEventPage(List<FailedEventView> events, int offset, int limit, boolean hasMore, long totalFailed) {}

    public record RedriveRequest(
            UUID paymentId,
            @Size(max = MAX_REDRIVE) List<UUID> eventIds) {}

    public record RedriveResult(int redrivenCount, List<UUID> redrivenEventIds,
                                long stillBlockedPaymentCount, long remainingFailedCount) {}

    public record ShadowRequest(
            boolean enabled,
            @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}") String candidateVersion) {}
}
