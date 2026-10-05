package com.payflow.notificationservice.service;

/**
 * Resolved recipient for one notification, plus why. Carrying the reason makes
 * the degraded path auditable instead of invisible.
 */
public record RecipientDecision(String email, boolean suppressed, String reason) {

    static RecipientDecision send(String email, String reason) {
        return new RecipientDecision(email, false, reason);
    }

    static RecipientDecision suppress(String reason) {
        return new RecipientDecision(null, true, reason);
    }
}
