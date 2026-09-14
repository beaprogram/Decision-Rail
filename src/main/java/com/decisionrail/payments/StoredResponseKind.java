package com.decisionrail.payments;

/**
 * What shape a stored idempotent response has.
 *
 * <p>Persisted beside the response body rather than inferred from its JSON keys. Two records that
 * happen to share field names would be indistinguishable by inspection, and a guess that is right
 * today becomes wrong the moment a third command is added. Every row written before checkpoint 9 is
 * a {@link #PAYMENT}, which its migration states explicitly.
 */
public enum StoredResponseKind { PAYMENT, RETURN }
