package com.decisionrail.policy;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.decision.DecisionRuleSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and reads immutable policy versions, and turns a stored definition back into the
 * snapshot the pure engine evaluates.
 *
 * <p>A created version is a candidate and nothing more. Replaying it or enabling it for shadow
 * evaluation does not make it authoritative: the only policy that decides real payments is the
 * built-in one, and nothing in this class can change that. Promotion and approval workflow are
 * outside this phase by design, so there is no code path that could promote a candidate
 * accidentally.
 */
@Service
public class PolicyService {
    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    public static final String BUILTIN_ORIGIN = "BUILTIN";
    public static final String CANDIDATE_ORIGIN = "CANDIDATE";
    /** Reserved so a candidate cannot shadow the authoritative policy's identity. */
    private static final Set<String> RESERVED_VERSION_IDS = Set.of(DecisionEngine.RULE_SET_VERSION);

    private final PolicyStore store;
    private final ObjectMapper json;
    // Translating and validating a stored definition is pure and deterministic, so a version's
    // snapshot is cached after first use. Versions are immutable, which is what makes this safe.
    private final Map<String, DecisionRuleSet> snapshots = new ConcurrentHashMap<>();

    public PolicyService(PolicyStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    /** Outcome of a create request, so the caller can answer 201, 200, or 409 correctly. */
    public record Created(PolicyVersion version, boolean alreadyExisted) {}

    /**
     * Registers a candidate policy.
     *
     * <p>Retry-safe without an idempotency key, because the definition hash is the identity of
     * the content. Submitting the same version id with the same definition returns the existing
     * version; submitting the same id with a different definition is rejected rather than
     * rebinding a name that historical decisions and pinned jobs already refer to.
     */
    @Transactional
    public Created createCandidate(String versionId, JsonNode definition, String createdBy) {
        if (versionId == null || !versionId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new PolicyValidationException("$.versionId",
                    "versionId must be an identifier of 1 to 64 letters, digits, dots, underscores or hyphens");
        }
        if (RESERVED_VERSION_IDS.contains(versionId)) {
            throw new PolicyValidationException("$.versionId", versionId + " is reserved for the built-in policy");
        }
        DecisionRuleSet ruleSet = PolicySchema.toRuleSet(versionId, definition);
        String canonical = PolicyCanonicalizer.canonicalJson(ruleSet);
        String hash = PolicyCanonicalizer.hash(canonical);

        boolean created = store.insertIfAbsent(versionId, canonical, hash, CANDIDATE_ORIGIN, ruleSet.rules().size(), createdBy);
        PolicyVersion stored = store.find(versionId);
        if (!created && !hash.equals(stored.definitionHash())) {
            throw new PolicyVersionConflictException(versionId, stored.definitionHash(), hash);
        }
        if (created) {
            log.info("Registered candidate policy {} with {} rules and hash {}", versionId, ruleSet.rules().size(), hash);
        }
        return new Created(stored, !created);
    }

    public PolicyVersion require(String versionId) {
        PolicyVersion version = store.find(versionId);
        if (version == null) {
            throw new PolicyNotFoundException(versionId);
        }
        return version;
    }

    public List<PolicyVersion> list(int limit) {
        return store.list(Math.clamp(limit, 1, 200));
    }

    /** Rebuilds the evaluable snapshot from a stored canonical definition. */
    public DecisionRuleSet snapshot(String versionId) {
        return snapshots.computeIfAbsent(versionId, id -> {
            PolicyVersion version = require(id);
            try {
                return PolicySchema.toRuleSet(id, json.readTree(version.definition()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
                throw new IllegalStateException("Stored policy definition for " + id + " is not readable JSON", unreadable);
            }
        });
    }

    /**
     * Records the built-in policy so replay reports can name it, and verifies it has not changed
     * meaning under a name that history already refers to.
     *
     * <p>A mismatch fails startup on purpose. Stored decisions and pinned replay jobs record a
     * policy version identifier; if the code behind that identifier were allowed to change, every
     * one of those records would silently start describing rules that were never applied.
     */
    @Transactional
    public void registerBuiltinPolicy(DecisionEngine engine) {
        DecisionRuleSet builtin = engine.activeRuleSet();
        String canonical = PolicyCanonicalizer.canonicalJson(builtin);
        String hash = PolicyCanonicalizer.hash(canonical);
        boolean created = store.insertIfAbsent(builtin.version(), canonical, hash, BUILTIN_ORIGIN,
                builtin.rules().size(), "application");
        if (created) {
            log.info("Recorded built-in policy {} with hash {}", builtin.version(), hash);
            return;
        }
        PolicyVersion stored = store.find(builtin.version());
        if (!hash.equals(stored.definitionHash())) {
            throw new IllegalStateException(("Built-in policy %s no longer matches the definition already recorded "
                    + "for that identifier (stored hash %s, current hash %s). Historical decisions name this version, "
                    + "so its meaning must not change. Introduce a new version identifier instead.")
                    .formatted(builtin.version(), stored.definitionHash(), hash));
        }
    }

    /** An attempt to bind an existing version identifier to a different definition. */
    public static class PolicyVersionConflictException extends RuntimeException {
        public PolicyVersionConflictException(String versionId, String storedHash, String submittedHash) {
            super(("Policy version %s already exists with definition hash %s; the submitted definition hashes to %s. "
                    + "A policy version is immutable: create a new version identifier.")
                    .formatted(versionId, storedHash, submittedHash));
        }
    }

    public static class PolicyNotFoundException extends RuntimeException {
        public PolicyNotFoundException(String versionId) {
            super("Policy version " + versionId + " was not found.");
        }
    }
}
