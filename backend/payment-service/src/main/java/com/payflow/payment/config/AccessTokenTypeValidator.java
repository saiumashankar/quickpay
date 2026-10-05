package com.payflow.payment.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects refresh tokens being used as bearer credentials.
 * Without this, a 7 day refresh token would be accepted on every API call.
 */
public class AccessTokenTypeValidator implements OAuth2TokenValidator<Jwt> {

    private static final String EXPECTED_TYPE = "ACCESS";

    private static final OAuth2Error INVALID_TYPE = new OAuth2Error(
            "invalid_token", "Token is not an access token", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String type = token.getClaimAsString("type");
        if (EXPECTED_TYPE.equals(type)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_TYPE);
    }
}
