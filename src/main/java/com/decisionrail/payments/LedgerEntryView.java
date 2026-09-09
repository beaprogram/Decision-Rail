package com.decisionrail.payments;

import java.util.UUID;

public record LedgerEntryView(UUID id, UUID journalId, String ledgerAccount, String side,
                              long amountMinor, String currency) {}
