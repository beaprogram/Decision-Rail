package com.decisionrail.shadow;

import java.time.Instant;

/**
 * Current shadow configuration.
 *
 * <p>{@code candidateVersion} being enabled here grants it no authority whatsoever. It selects a
 * policy to observe alongside live decisions; the authoritative policy is unchanged and a
 * candidate cannot become authoritative through this setting.
 */
public record ShadowSettingsView(
        boolean enabled,
        String candidateVersion,
        String candidateHash,
        String updatedBy,
        Instant updatedAt,
        long pendingTasks,
        long failedTasks,
        long comparisons,
        long divergences) {}
