package com.decisionrail.decision;

/** A matched synthetic rule and the exact points it contributed to this decision. */
public record ReasonContribution(String code, String description, int scoreContribution) {
    public ReasonContribution {
        if (code == null || code.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("reason code and description are required");
        }
        if (scoreContribution < 0 || scoreContribution > 100) {
            throw new IllegalArgumentException("reason scoreContribution must be between 0 and 100");
        }
    }
}
