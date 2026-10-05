package com.payflow.auth.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one place a handle is turned into its canonical stored form.
 *
 * A handle is what a person types into a transfer ("@Sai123", "sai123") and the
 * stored form is what makes it a stable identity ("sai123"). Without one rule
 * for that conversion, "@Sai123" and "sai123" become two different wallets and
 * money is sent to an account that does not exist. Registration, uniqueness
 * checks and handle lookup all go through here so they cannot disagree.
 *
 * The regex is deliberately stricter than the password rules: a handle is shown
 * in notifications and merchant webhooks, so it is restricted to characters
 * that cannot be confused with each other or used to fake another user's
 * handle in a rendered notification.
 */
@Service
public class HandleService {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9._]{2,29}$");

    /**
     * @return the canonical handle, or empty when the input cannot be a handle
     */
    public Optional<String> normalize(String handle) {
        if (handle == null) {
            return Optional.empty();
        }
        String trimmed = handle.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        String canonical = trimmed.toLowerCase(Locale.ROOT);
        return VALID.matcher(canonical).matches() ? Optional.of(canonical) : Optional.empty();
    }

    public String normalizeOrThrow(String handle) {
        return normalize(handle).orElseThrow(
                () -> new com.payflow.auth.exception.ApiException("Handle must be 3 to 30 characters, letters, digits, dot or underscore"));
    }
}
