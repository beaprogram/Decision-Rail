package com.decisionrail.events;

/** A consumed record that cannot be applied. {@link #reason()} matches consumer_quarantine.reason. */
public class EventContractException extends RuntimeException {
    private final String reason;

    public EventContractException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EventContractException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
