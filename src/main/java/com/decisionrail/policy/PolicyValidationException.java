package com.decisionrail.policy;

/** A candidate policy definition that is rejected. The message names the offending path. */
public class PolicyValidationException extends RuntimeException {
    private final String path;

    public PolicyValidationException(String path, String message) {
        super(message);
        this.path = path;
    }

    public String path() { return path; }

    @Override
    public String getMessage() {
        return path == null || path.isBlank() ? super.getMessage() : path + ": " + super.getMessage();
    }
}
