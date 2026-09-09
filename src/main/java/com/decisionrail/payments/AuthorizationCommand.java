package com.decisionrail.payments;

import java.util.UUID;

public record AuthorizationCommand(UUID accountId, long amountMinor, String currency, String country) {}
