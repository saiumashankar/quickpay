package com.payflow.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules here have to match auth-service's HandleService exactly. If the two
 * disagree about what a handle is, a transfer is either rejected for a handle
 * that exists or resolved to an account that does not, so the shared cases are
 * asserted on both sides.
 */
@DisplayName("a typed handle is reduced to the one form auth-service stores")
class HandleTest {

    @Test
    @DisplayName("the @ prefix is optional")
    void atPrefixIsOptional() {
        assertThat(Handle.canonicalize("@sai123")).contains("sai123");
        assertThat(Handle.canonicalize("sai123")).contains("sai123");
    }

    @Test
    @DisplayName("case is not part of the identity")
    void caseIsIgnored() {
        assertThat(Handle.canonicalize("@Sai123")).contains("sai123");
        assertThat(Handle.canonicalize("  MERCHANT ")).contains("merchant");
    }

    @Test
    @DisplayName("anything that could not be a handle is refused before any lookup")
    void invalidHandlesAreRefused() {
        assertThat(Handle.canonicalize("!!")).isEmpty();
        assertThat(Handle.canonicalize("sai 123")).isEmpty();
        assertThat(Handle.canonicalize("sai@123")).isEmpty();
        assertThat(Handle.canonicalize("ab")).isEmpty();
        assertThat(Handle.canonicalize(null)).isEmpty();
        assertThat(Handle.canonicalize("")).isEmpty();
    }

    @Test
    @DisplayName("dots and underscores are allowed inside a handle")
    void dotsAndUnderscoresAreAllowed() {
        assertThat(Handle.canonicalize("@sai.kumar_1")).contains("sai.kumar_1");
    }
}
