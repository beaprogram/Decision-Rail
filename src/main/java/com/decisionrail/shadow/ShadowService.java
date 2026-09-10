package com.decisionrail.shadow;

import com.decisionrail.payments.PaymentException;
import com.decisionrail.policy.PolicyService;
import com.decisionrail.policy.PolicyVersion;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes shadow configuration, and serves merchant-scoped comparisons.
 *
 * <p>Enabling a candidate here selects a policy to evaluate alongside live decisions. It does not
 * promote anything: the authoritative policy is the built-in one, and there is no code path in this
 * service that could make a candidate decide a real payment.
 */
@Service
public class ShadowService {
    private static final Logger log = LoggerFactory.getLogger(ShadowService.class);

    private final ShadowStore store;
    private final PolicyService policies;
    private final Clock clock;

    public ShadowService(ShadowStore store, PolicyService policies, Clock clock) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ShadowSettingsView settings() {
        return describe(store.settings());
    }

    /**
     * Enables or disables shadow evaluation.
     *
     * <p>Idempotent: applying the same configuration twice leaves the same state, so a retried
     * request needs no key. Disabling leaves already queued tasks and recorded comparisons alone;
     * it stops new work being enqueued rather than erasing evidence.
     */
    @Transactional
    public ShadowSettingsView configure(boolean enabled, String candidateVersion, String updatedBy) {
        if (enabled && (candidateVersion == null || candidateVersion.isBlank())) {
            throw new PaymentException("INVALID_SHADOW_CONFIGURATION", 400,
                    "A candidate policy version is required to enable shadow evaluation.");
        }
        String resolved = null;
        if (candidateVersion != null && !candidateVersion.isBlank()) {
            PolicyVersion version = policies.require(candidateVersion);
            // Fails now if the stored definition cannot be rebuilt, rather than once per task.
            policies.snapshot(version.versionId());
            resolved = version.versionId();
        }
        store.updateSettings(enabled, resolved, updatedBy, clock.instant());
        log.info("Shadow evaluation {} for candidate {} by {}", enabled ? "enabled" : "disabled", resolved, updatedBy);
        return describe(store.settings());
    }

    @Transactional(readOnly = true)
    public List<ShadowComparisonView> comparisons(String merchantId, UUID paymentId) {
        return store.comparisons(merchantId, paymentId);
    }

    @Transactional(readOnly = true)
    public List<ShadowComparisonView> recent(String merchantId, boolean divergedOnly, int limit) {
        return store.recentComparisons(merchantId, divergedOnly, Math.clamp(limit, 1, 200));
    }

    private ShadowSettingsView describe(ShadowStore.Settings settings) {
        String hash = settings.candidateVersion() == null ? null
                : policies.require(settings.candidateVersion()).definitionHash();
        return new ShadowSettingsView(settings.enabled(), settings.candidateVersion(), hash,
                settings.updatedBy(), settings.updatedAt(),
                store.countTasks("PENDING"), store.countTasks("FAILED"),
                store.countComparisons(false), store.countComparisons(true));
    }
}
