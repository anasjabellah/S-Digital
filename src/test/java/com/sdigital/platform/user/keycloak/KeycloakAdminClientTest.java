package com.sdigital.platform.user.keycloak;

import com.sdigital.platform.user.keycloak.exception.KeycloakUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the HTTP contract of {@link KeycloakAdminClient} against a mocked Keycloak
 * server: token acquisition (client_credentials) and the realm-role endpoints.
 *
 * <p>No real Keycloak is contacted; only request shape and response parsing are asserted.</p>
 */
class KeycloakAdminClientTest {

    private static final String SERVER = "http://keycloak.test";
    private static final String REALM = "s-digital";
    private static final String TOKEN_PATH = SERVER + "/realms/" + REALM + "/protocol/openid-connect/token";

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
    void findUserIdReturnsIdWhenUserExists() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users?exact=true&max=2&username=sub-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("[{\"id\":\"kc-1\",\"enabled\":true}]", MediaType.APPLICATION_JSON));

        assertThat(client.findUserId("sub-1")).isEqualTo("kc-1");
        server.verify();
    }

    @Test
    void findUserIdReturnsNullWhenUserMissing() {
        expectToken();
        server.expect(requestTo(SERVER + "/admin/realms/" + REALM + "/users?exact=true&max=2&username=sub-x"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findUserId("sub-x")).isNull();
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

    private void expectToken() {
        server.expect(requestTo(TOKEN_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"test-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }
}
