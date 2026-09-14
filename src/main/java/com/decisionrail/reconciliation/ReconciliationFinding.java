package com.decisionrail.reconciliation;

import java.util.List;
import java.util.UUID;

/**
 * One discrepancy, stated so that someone can act on it without running a second investigation.
 *
 * <p>Every finding names the resource, what the evidence says the value should be, what is actually
 * recorded, the difference, the currency, and the operations or journals that support the expectation.
 * A report that said only "account does not reconcile" would require the reader to redo the whole
 * derivation by hand, which is the same as not reporting it.
 *
 * @param deltaMinor actual minus expected, so a positive number means more money is recorded than the
 *                   evidence accounts for. Null where a difference is not a quantity - a missing
 *                   journal is not "off by" anything.
 * @param references the evidence: the journals, return operations and payments the expectation was
 *                   derived from, so the reader starts from the rows rather than from a number
 */
public record ReconciliationFinding(
        String type,
        Severity severity,
        String resourceType,
        UUID resourceId,
        String currency,
        Long expectedMinor,
        Long actualMinor,
        Long deltaMinor,
        String detail,
        List<Reference> references) {

    /**
     * CRITICAL means recorded money disagrees with the evidence for it. WARNING means the evidence is
     * incomplete or inconsistent in a way that does not itself move a balance. Neither is a licence to
     * change anything: this report never writes.
     */
    public enum Severity { CRITICAL, WARNING }

    public record Reference(String kind, UUID id) {}

    static ReconciliationFinding of(String type, Severity severity, String resourceType, UUID resourceId,
                                    String currency, Long expected, Long actual, String detail,
                                    List<Reference> references) {
        Long delta = expected == null || actual == null ? null : actual - expected;
        return new ReconciliationFinding(type, severity, resourceType, resourceId, currency,
                expected, actual, delta, detail, List.copyOf(references));
    }
}
