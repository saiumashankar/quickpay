package com.payflow.payment.service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Canonicalises a handle exactly as it is typed into a transfer.
 *
 * This mirrors auth-service's HandleService on purpose. It cannot ask
 * auth-service to canonicalise on every transfer just to save a local regex,
 * and the two must agree: if this side lowercased and auth-service did not,
 * "@Sai123" would resolve here and 404 there.
 *
 * auth-service remains the authority. This produces the candidate that is sent
 * for resolution, and auth-service re-normalises and rejects anything that is
 * not a handle it knows. So the worst a disagreement can do is a lookup that
 * comes back empty, never a transfer to the wrong wallet.
 */
public final class Handle {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9._]{2,29}$");

    private Handle() {
    }

    /**
     * @return the canonical handle, or empty when the input cannot be a handle
     */
    public static Optional<String> canonicalize(String handle) {
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
}
