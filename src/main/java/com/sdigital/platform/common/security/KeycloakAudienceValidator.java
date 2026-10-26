package com.sdigital.platform.common.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Validates that a Keycloak access token was issued for the S-Digital API.
 *
 * <p>The expected audience is compared against the token's {@code aud} claim. A token with
 * no {@code aud} claim, an empty audience, or a different audience is rejected, so a token
 * minted for another client of the same realm cannot be replayed against this API.</p>
 *
 * <p>The validator fails closed when no expected audience is configured: a blank audience
 * rejects every token instead of silently disabling the check. The error description never
 * echoes the token or the configured audience.</p>
 */
public final class KeycloakAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final String MISSING_OR_INVALID_AUDIENCE =
            "The token is not intended for this API (missing or invalid audience)";
    private static final String NOT_CONFIGURED =
            "Audience validation is not configured";

    private final String expectedAudience;

    public KeycloakAudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience == null ? null : expectedAudience.trim();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!StringUtils.hasText(expectedAudience)) {
            return failure(NOT_CONFIGURED);
        }
        if (token == null) {
            return failure(MISSING_OR_INVALID_AUDIENCE);
        }
        List<String> audiences;
        try {
            audiences = token.getAudience();
        } catch (IllegalArgumentException malformedClaim) {
            // A non-string / non-list `aud` claim is a malformed token, not a server error.
            return failure(MISSING_OR_INVALID_AUDIENCE);
        }
        if (audiences != null && audiences.contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return failure(MISSING_OR_INVALID_AUDIENCE);
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
