package com.sdigital.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for {@link KeycloakAudienceValidator}.
 *
 * <p>The validator is exercised without a decoder or a Keycloak server, so the audience
 * policy itself is asserted independently of the JWT signature/issuer chain.</p>
 */
class KeycloakAudienceValidatorTest {

    private static final String EXPECTED = "s-digital-api";

    private final KeycloakAudienceValidator validator = new KeycloakAudienceValidator(EXPECTED);

    @Test
    void acceptsTokenWithExpectedAudience() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of(EXPECTED)));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void acceptsTokenWhenExpectedAudienceIsOneOfSeveral() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of("account", EXPECTED, "another-api")));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithoutAudience() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(null));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of("another-api")));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsEmptyAudience() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of()));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsAudienceWithDifferentCase() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of("S-DIGITAL-API")));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsMalformedAudienceClaimWithoutThrowing() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("test-user")
                .claim("aud", 123)
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsNullToken() {
        assertThat(validator.validate(null).hasErrors()).isTrue();
    }

    @Test
    void rejectsEveryTokenWhenExpectedAudienceIsBlank() {
        OAuth2TokenValidatorResult result = new KeycloakAudienceValidator("   ")
                .validate(jwt(List.of(EXPECTED)));

        assertThat(result.hasErrors()).isTrue();
    }

    private static Jwt jwt(List<String> audiences) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("test-user");
        if (audiences != null) {
            builder.claim("aud", audiences);
        }
        return builder.build();
    }
}
