package com.decisionrail.api;

import com.decisionrail.events.InboxStore;
import com.decisionrail.events.PaymentActivityView;
import com.decisionrail.payments.PaymentException;
import com.decisionrail.payments.PaymentService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant reads of the event-derived activity projection.
 *
 * <p>Tenancy is enforced in the query, not by filtering afterwards. A projection row that
 * belongs to another merchant is indistinguishable from one that does not exist.
 */
@RestController
@RequestMapping("/v1")
public class ActivityController {
    private final InboxStore projection;
    private final PaymentService payments;

    public ActivityController(InboxStore projection, PaymentService payments) {
        this.projection = projection;
        this.payments = payments;
    }

    @GetMapping("/payments/{id}/activity")
    public PaymentActivityView activity(Principal principal, @PathVariable UUID id) {
        // Confirms the caller owns the payment first, so a not-yet-projected payment reports
        // "not projected" instead of leaking whether another merchant's payment exists.
        payments.payment(principal.getName(), id);
        PaymentActivityView view = projection.activity(principal.getName(), id);
        if (view == null) {
            throw new PaymentException("ACTIVITY_NOT_PROJECTED", 404,
                    "No delivered event has been projected for this payment yet.");
        }
        return view;
    }

    @GetMapping("/activity")
    public List<PaymentActivityView> recent(Principal principal,
            @RequestParam(defaultValue = "20") int limit) {
        // Clamped rather than rejected: a listing bound is a server concern, and an unbounded
        // limit is the only genuinely unsafe value.
        return projection.recentActivity(principal.getName(), Math.clamp(limit, 1, 200));
    }
}
