package com.decisionrail.payments;

import com.decisionrail.events.ConsumptionRecordView;
import com.decisionrail.events.DeliveryRecordView;
import com.decisionrail.events.InboxStore;
import com.decisionrail.events.OutboxStore;
import com.decisionrail.events.PaymentActivityView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant-scoped reads that the operator dashboard needs and the command service does not provide:
 * account listing, authoritative payment search, and a lifecycle timeline.
 *
 * <p>Read-only by construction. It writes nothing, holds no locks beyond its read transaction, and
 * creates no alternative route to a financial mutation; {@link PaymentService} remains the only way a
 * payment changes.
 *
 * <p>Ownership is never inferred from a request. Every query takes the merchant resolved from
 * authentication and carries it into the SQL, so a resource belonging to another merchant is not
 * filtered out after the fact but never selected.
 */
@Service
public class PaymentReadService {
    /** Bounded so one payment with an unusual amount of history cannot produce an unbounded response. */
    private static final int MAX_TIMELINE_EVENTS = 200;
    private static final int MAX_TIMELINE_COMMANDS = 200;
    private static final int MAX_TIMELINE_CONSUMPTIONS = 500;
    public static final int MAX_ACCOUNTS = 100;

    private final PaymentStore store;
    private final OutboxStore outbox;
    private final InboxStore inbox;

    public PaymentReadService(PaymentStore store, OutboxStore outbox, InboxStore inbox) {
        this.store = store;
        this.outbox = outbox;
        this.inbox = inbox;
    }

    @Transactional(readOnly = true)
    public List<AccountView> accounts(String merchant) {
        return store.accounts(merchant, MAX_ACCOUNTS);
    }

    @Transactional(readOnly = true)
    public PaymentSearchPage search(String merchant, PaymentSearchQuery query) {
        return store.search(merchant, query);
    }

    /**
     * Assembles a payment's lifecycle from the three kinds of evidence that actually exist, keeping
     * them distinct.
     *
     * <p>Ownership is established first by reading the payment itself, which throws if it belongs to
     * another merchant. Only then are its events read, so the delivery tables are never queried for a
     * payment the caller cannot see.
     */
    @Transactional(readOnly = true)
    public PaymentTimelineView timeline(String merchant, UUID paymentId) {
        PaymentView payment = store.payment(merchant, paymentId, false);

        List<DeliveryRecordView> records = outbox.eventsForPayment(paymentId, MAX_TIMELINE_EVENTS);
        Map<UUID, List<ConsumptionRecordView>> consumptions =
                inbox.consumptionsForPayment(paymentId, MAX_TIMELINE_CONSUMPTIONS);
        List<PaymentTimelineView.EventEntry> events = new ArrayList<>(records.size());
        for (DeliveryRecordView record : records) {
            List<ConsumptionRecordView> consumed = consumptions.getOrDefault(record.eventId(), List.of());
            events.add(new PaymentTimelineView.EventEntry(
                    record.eventId(), record.aggregateSequence(), record.eventType(), record.occurredAt(),
                    record.status(), record.publishedAt(), record.attempts(),
                    record.brokerPartition(), record.brokerOffset(), record.failureKind(),
                    consumed.stream()
                            .map(entry -> new PaymentTimelineView.ConsumerEntry(entry.consumerGroup(), entry.consumedAt()))
                            .toList()));
        }

        PaymentActivityView activity = inbox.activity(merchant, paymentId);
        PaymentTimelineView.ProjectionState projection = activity == null ? null
                : new PaymentTimelineView.ProjectionState(activity.lastStatus(), activity.lastEventType(),
                        activity.lastSequence(), activity.appliedEventCount(),
                        activity.firstEventAt(), activity.lastEventAt());

        return new PaymentTimelineView(payment.id(), payment.accountId(), payment.status(),
                payment.createdAt(), payment.updatedAt(),
                store.commands(merchant, paymentId, MAX_TIMELINE_COMMANDS), List.copyOf(events), projection);
    }
}
