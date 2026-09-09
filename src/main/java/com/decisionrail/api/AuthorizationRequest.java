package com.decisionrail.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record AuthorizationRequest(
        @NotNull UUID accountId,
        @NotNull @Positive @Max(1_000_000_000_000L) Long amountMinor,
        @NotNull @Pattern(regexp = "[A-Za-z]{3}") String currency,
        @NotNull @Pattern(regexp = "[A-Za-z]{2}") String country) {}
