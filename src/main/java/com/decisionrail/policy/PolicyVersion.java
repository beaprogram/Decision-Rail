package com.decisionrail.policy;

import java.time.Instant;

/**
 * A stored, immutable policy version.
 *
 * @param definition     the canonical definition, which is also exactly what gets evaluated
 * @param definitionHash SHA-256 of the canonical definition
 * @param origin         BUILTIN for the authoritative in-code policy, CANDIDATE for a submitted
 *                       one. A candidate never becomes authoritative by being replayed or
 *                       enabled for shadow evaluation; promotion is not part of this phase.
 */
public record PolicyVersion(
        String versionId,
        String definition,
        String definitionHash,
        String origin,
        int ruleCount,
        String createdBy,
        Instant createdAt) {}
