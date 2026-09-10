package com.decisionrail.events;

/**
 * A send that did not produce a broker acknowledgement.
 *
 * @param retryable      false for defects in the event itself (serialization, contract), which
 *                       no amount of retrying can fix and which must not count against the
 *                       dependency breaker.
 * @param shortCircuited true when the circuit breaker refused to attempt the send. The broker
 *                       was never contacted, so this is not an attempt against the retry
 *                       budget: charging it would let a few seconds of breaker protection
 *                       terminally fail an entire backlog that was never actually tried.
 */
public class BrokerSendException extends RuntimeException {
    private final boolean retryable;
    private final boolean shortCircuited;

    public BrokerSendException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, false, cause);
    }

    public BrokerSendException(String message, boolean retryable) {
        this(message, retryable, false, null);
    }

    public BrokerSendException(String message, boolean retryable, boolean shortCircuited, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.shortCircuited = shortCircuited;
    }

    public boolean retryable() { return retryable; }

    public boolean shortCircuited() { return shortCircuited; }
}
