package com.payflow.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the handle a person types and the handle stored are the same identity")
class HandleServiceTest {

    private final HandleService handleService = new HandleService();

    @Test
    @DisplayName("the @ prefix a person naturally types is not part of the identity")
    void atPrefixIsOptional() {
        assertThat(handleService.normalize("@sai123")).contains("sai123");
        assertThat(handleService.normalize("sai123")).contains("sai123");
        assertThat(handleService.normalize("@sai123")).isEqualTo(handleService.normalize("sai123"));
    }

    @Test
    @DisplayName("case is not part of the identity either")
    void caseIsNotPartOfTheIdentity() {
        assertThat(handleService.normalize("@Sai123")).contains("sai123");
        assertThat(handleService.normalize("SAI123")).contains("sai123");
        assertThat(handleService.normalize("sAi123")).isEqualTo(handleService.normalize("sai123"));
    }

    @Test
    @DisplayName("surrounding whitespace from a copy and paste is ignored")
    void whitespaceIsIgnored() {
        assertThat(handleService.normalize("  @sai123  ")).contains("sai123");
    }

    @Test
    @DisplayName("handles too short to be an identity are refused")
    void shortHandlesAreRefused() {
        assertThat(handleService.normalize("@ab")).isEmpty();
        assertThat(handleService.normalize("a")).isEmpty();
    }

    @Test
    @DisplayName("characters that could disguise one handle as another are refused")
    void confusingCharactersAreRefused() {
        // A leading @ or a space inside the handle is what would let a rendered
        // notification read as somebody else's handle.
        assertThat(handleService.normalize("sai 123")).isEmpty();
        assertThat(handleService.normalize("sai@123")).isEmpty();
        assertThat(handleService.normalize(".sai123")).isEmpty();
        assertThat(handleService.normalize("_sai123")).isEmpty();
        assertThat(handleService.normalize("<script>")).isEmpty();
    }

    @Test
    @DisplayName("null and blank are refused rather than throwing")
    void nullAndBlankAreRefused() {
        assertThat(handleService.normalize(null)).isEmpty();
        assertThat(handleService.normalize("   ")).isEmpty();
        assertThat(handleService.normalize("@")).isEmpty();
    }

    @Test
    @DisplayName("dots and underscores are allowed inside a handle")
    void dotsAndUnderscoresAreAllowed() {
        assertThat(handleService.normalize("sai.kumar_1")).contains("sai.kumar_1");
    }

    @Test
    @DisplayName("normalizeOrThrow reports the rule rather than failing obscurely")
    void normalizeOrThrowExplainsTheRule() {
        assertThat(handleService.normalizeOrThrow("@Sai123")).isEqualTo("sai123");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> handleService.normalizeOrThrow("!!"))
                .isInstanceOf(com.payflow.auth.exception.ApiException.class)
                .hasMessageContaining("3 to 30 characters");
    }
}
