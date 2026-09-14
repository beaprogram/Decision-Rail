package com.decisionrail.payments;

/**
 * The two ways captured money goes back, which share one budget and differ in what they claim.
 *
 * <p>{@link #REFUND} returns some or all of a capture and can happen repeatedly until the budget is
 * exhausted. {@link #REVERSAL} says the capture itself should not have stood: it is all or nothing,
 * and is refused once anything has already been returned. See {@code docs/adr/0007} for why a
 * reversal is not quietly downgraded to a refund of the remainder.
 *
 * <p>Neither is an authorization reversal. Releasing a hold before capture is {@code void}, which
 * moves no money and writes no journal; that behaviour is unchanged.
 */
public enum ReturnType { REFUND, REVERSAL }
