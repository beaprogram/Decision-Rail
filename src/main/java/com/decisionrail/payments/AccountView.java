package com.decisionrail.payments;

import java.util.UUID;

public record AccountView(UUID id, String currency, long balanceMinor, long heldMinor, long availableMinor) {}
