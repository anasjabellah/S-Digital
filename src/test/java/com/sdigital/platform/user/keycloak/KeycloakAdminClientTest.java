package com.sdigital.platform.user.keycloak;

import com.sdigital.platform.user.keycloak.exception.KeycloakUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the HTTP contract of {@link KeycloakAdminClient} against a mocked Keycloak
 * server: token acquisition (client_credentials), user lookup/provisioning and the
 * realm-role endpoints.
 *
 * <p>No real Keycloak is contacted; only request shape and response parsing are asserted.</p>
 */
class KeycloakAdminClientTest {

    private static final String SERVER = "http://keycloak.test";
    private static final String REALM = "s-digital";
    private static final String TOKEN_PATH = SERVER + "/realms/" + REALM + "/protocol/openid-connect/token";
    private static final String USERS_URL = SERVER + "/admin/realms/" + REALM + "/users";

    private MockRestServiceServer server;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setServerUrl(SERVER);
        properties.setRealm(REALM);
        properties.setClientId("backend-admin");
        properties.setClientSecret("s3cr3t");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KeycloakAdminClient(properties, builder);
    }

    @Test
    void findUserIdByInternalIdUsesByIdEndpointAndReturnsId() {
        expectToken();
        server.expect(requestTo(USERS_URL + "/kc-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"id\":\"kc-1\",\"enabled\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.findUserIdByInternalId("kc-1")).isEqualTo("kc-1");
        server.verify();
    }

    @Test
    void findUserIdByInternalIdReturnsNullWhenUserMissing() {
        expectToken();
        server.expect(requestTo(USERS_URL + "/kc-missing"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findUserIdByInternalId("kc-missing")).isNull();
        server.verify();
    }

    @Test
    void findUserIdByInternalIdClassifiesUpstreamError() {
        expectToken();
        server.expect(requestTo(USERS_URL + "/kc-1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.findUserIdByInternalId("kc-1"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.SERVER_ERROR));
    }

    @Test
    void findUserIdByInternalIdClassifiesUnauthorized() {
        expectToken();
        server.expect(requestTo(USERS_URL + "/kc-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.findUserIdByInternalId("kc-1"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.UNAUTHENTICATED));
    }

    @Test
    void findUserIdByInternalIdRejectsMalformedRepresentation() {
        expectToken();
        server.expect(requestTo(USERS_URL + "/kc-1"))
                .andRespond(withSuccess("{\"enabled\":true}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findUserIdByInternalId("kc-1"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.SERVER_ERROR));
    }

    @Test
    void findUserIdByInternalIdReturnsNullForBlankIdWithoutCallingKeycloak() {
        assertThat(client.findUserIdByInternalId("   ")).isNull();
        server.verify();
    }

    @Test
    void isUserEnabledReflectsFlag() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1"))
                .andRespond(withSuccess("{\"id\":\"kc-1\",\"enabled\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.isUserEnabled("kc-1")).isFalse();
    }

    @Test
    void listRealmRolesReturnsNames() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andRespond(withSuccess("[{\"name\":\"MANAGER\"},{\"name\":\"USER\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.listRealmRoles("kc-1")).containsExactly("MANAGER", "USER");
    }

    @Test
    void addRealmRoleResolvesRoleThenPostsMapping() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/roles/MANAGER"))
                .andRespond(withSuccess("{\"id\":\"role-1\",\"name\":\"MANAGER\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.addRealmRole("kc-1", "MANAGER")).isTrue();
        server.verify();
    }

    @Test
    void addRealmRoleReturnsFalseWhenRoleUnknown() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/roles/GHOST"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.NOT_FOUND));

        assertThat(client.addRealmRole("kc-1", "GHOST")).isFalse();
    }

    @Test
    void removeRealmRoleResolvesRoleThenDeletesMapping() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/roles/MANAGER"))
                .andRespond(withSuccess("{\"id\":\"role-1\",\"name\":\"MANAGER\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.removeRealmRole("kc-1", "MANAGER")).isTrue();
        server.verify();
    }

    @Test
    void upstreamUnauthorizedIsClassified() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.listRealmRoles("kc-1"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.UNAUTHENTICATED));
    }

    @Test
    void upstreamForbiddenIsClassified() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.listRealmRoles("kc-1"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.FORBIDDEN));
    }

    @Test
    void upstreamTooManyRequestsIsClassified() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.listRealmRoles("kc-1"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.RATE_LIMITED));
    }

    @Test
    void upstreamServerErrorIsClassified() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.listRealmRoles("kc-1"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.SERVER_ERROR));
    }

    @Test
    void mutationUpstreamFailureIsClassifiedAndNoBodyLeaks() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/roles/MANAGER"))
                .andRespond(withSuccess("{\"id\":\"role-1\",\"name\":\"MANAGER\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users/kc-1/role-mappings/realm"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.addRealmRole("kc-1", "MANAGER"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .hasMessageNotContaining("secret");
    }

    // --- user provisioning ---------------------------------------------------------------------

    @Test
    void findUserIdByUsernameReturnsInternalId() {
        expectToken();
        server.expect(requestTo(USERS_URL + "?exact=true&max=2&username=ada"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("[{\"id\":\"kc-1\",\"enabled\":true}]", MediaType.APPLICATION_JSON));

        assertThat(client.findUserIdByUsername("ada")).isEqualTo("kc-1");
        server.verify();
    }

    @Test
    void findUserIdByUsernameReturnsNullWhenMissing() {
        expectToken();
        server.expect(requestTo(USERS_URL + "?exact=true&max=2&username=ada"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findUserIdByUsername("ada")).isNull();
    }

    @Test
    void findUserIdByUsernameRejectsAmbiguousResult() {
        expectToken();
        server.expect(requestTo(USERS_URL + "?exact=true&max=2&username=ada"))
                .andRespond(withSuccess("[{\"id\":\"kc-1\"},{\"id\":\"kc-2\"}]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findUserIdByUsername("ada"))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.SERVER_ERROR));
    }

    @Test
    void createUserPostsProfileOnlyAndReadsInternalIdFromLocation() {
        expectToken();
        server.expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().json("""
                        {
                          "username": "ada@example.com",
                          "email": "ada@example.com",
                          "firstName": "Ada",
                          "lastName": "Lovelace",
                          "enabled": true
                        }
                        """, true))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("credentials"))))
                .andExpect(content().string(not(containsString("requiredActions"))))
                .andExpect(content().string(not(containsString("realmRoles"))))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .location(URI.create(USERS_URL + "/kc-new")));

        assertThat(client.createUser("ada@example.com", "ada@example.com", "Ada", "Lovelace", true))
                .isEqualTo("kc-new");
        server.verify();
    }

    @Test
    void createUserFallsBackToExactUsernameLookupWhenLocationIsMissing() {
        expectToken();
        server.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(USERS_URL + "?exact=true&max=2&username=ada"))
                .andRespond(withSuccess("[{\"id\":\"kc-new\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.createUser("ada", "ada@example.com", "Ada", "Lovelace", true))
                .isEqualTo("kc-new");
        server.verify();
    }

    @Test
    void createUserFallsBackWhenLocationIsMalformed() {
        expectToken();
        server.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.CREATED).location(URI.create(USERS_URL + "/")));
        server.expect(requestTo(USERS_URL + "?exact=true&max=2&username=ada"))
                .andRespond(withSuccess("[{\"id\":\"kc-new\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.createUser("ada", "ada@example.com", "Ada", "Lovelace", true))
                .isEqualTo("kc-new");
    }

    @Test
    void createUserThrowsWhenNoInternalIdCanBeResolved() {
        expectToken();
        server.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(USERS_URL + "?exact=true&max=2&username=ada"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createUser("ada", "ada@example.com", "Ada", "Lovelace", true))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.SERVER_ERROR))
                .hasMessageNotContaining("ada@example.com");
    }

    @Test
    void createUserConflictIsClassified() {
        expectToken();
        server.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.createUser("ada", "ada@example.com", "Ada", "Lovelace", true))
                .isInstanceOf(KeycloakUpstreamException.class)
                .satisfies(ex -> assertThat(((KeycloakUpstreamException) ex).getKind())
                        .isEqualTo(KeycloakUpstreamException.Kind.CONFLICT));
    }

    @Test
    void deleteUserSendsDelete() {
        expectToken();
        server.expect(requestTo(USERS_URL + "/kc-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteUser("kc-1");
        server.verify();
    }

    @Test
    void deleteUserTreatsNotFoundAsSuccess() {
        expectToken();
        server.expect(requestTo(USERS_URL + "/kc-1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.deleteUser("kc-1");
        server.verify();
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"test-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }
}
