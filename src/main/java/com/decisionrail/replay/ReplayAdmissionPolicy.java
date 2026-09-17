package com.decisionrail.replay;

/**
 * Whether a merchant may start another replay job right now.
 *
 * <p>Consulted inside the job-creation transaction, after the idempotency check and before the job
 * row exists, so a refusal rolls back with nothing written - no job, no membership, no request
 * record - and a same-key retry of a job that was created still replays it without being asked
 * again. An implementation that needs to serialise concurrent creators does so with the database,
 * inside this same transaction; that is what makes a "one at a time" limit true rather than
 * approximately true.
 *
 * <p>The default admits everything. The public demo supplies a policy for its shared visitor.
 */
@FunctionalInterface
public interface ReplayAdmissionPolicy {
    ReplayAdmissionPolicy UNLIMITED = merchantId -> {};

    /**
     * @throws com.decisionrail.payments.PaymentException with status 429 to refuse; the creating
     *         transaction rolls back and the caller is told when to try again
     */
    void admit(String merchantId);
}
