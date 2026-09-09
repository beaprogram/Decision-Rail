package com.decisionrail.payments;

public record CommandResult(PaymentView payment, int httpStatus, boolean replayed) {}
