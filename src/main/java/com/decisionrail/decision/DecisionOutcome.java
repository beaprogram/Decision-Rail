package com.decisionrail.decision;

public enum DecisionOutcome {
    APPROVE, REVIEW, DECLINE;

    public static DecisionOutcome fromScore(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        if (score >= 60) {
            return DECLINE;
        }
        return score >= 30 ? REVIEW : APPROVE;
    }
}
