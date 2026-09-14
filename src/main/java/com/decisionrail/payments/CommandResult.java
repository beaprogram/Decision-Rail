package com.decisionrail.payments;

/**
 * The outcome of a financial command, whatever shape its response takes.
 *
 * <p>Generic because a return does not answer with a payment. Storing every idempotent response as a
 * {@code PaymentView} and decoding it back as one was safe while authorize, capture and void were the
 * only commands; a refund receipt read through that assumption would either fail or, against a
 * lenient reader, succeed and hand back a half-populated payment. The stored kind now travels with
 * the response and decides the decoder.
 *
 * @param replayed true when this came from the durable idempotency record rather than from work done
 *                 now, which callers surface as {@code Idempotency-Replayed}
 */
public record CommandResult<T>(T body, int httpStatus, boolean replayed) {}
