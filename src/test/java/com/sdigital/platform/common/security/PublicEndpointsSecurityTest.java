package com.sdigital.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security checks against the fully wired application context.
 *
 * <p>These verify the public surface (health probe, OpenAPI document, Swagger UI) is
 * reachable anonymously while the business API is not, using the real
 * {@link SecurityConfig} and the real {@link KeycloakJwtDecoderConfig} fail-closed decoder
 * (no {@code KEYCLOAK_ISSUER_URI}). A PostgreSQL container backs the context so Flyway and
 * Hibernate validation run as in production.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublicEndpointsSecurityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorHealthIsAccessibleAnonymously() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * The health probe is public: it must stay reachable, but it must not become an anonymous
     * source of infrastructure details (database product, absolute filesystem paths, free disk
     * space, TLS certificate chains). Only the aggregate status may be exposed.
     */
    @Test
    void actuatorHealthDoesNotDiscloseInternalDetailsAnonymously() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(content().string(not(containsString("diskSpace"))))
                .andExpect(content().string(not(containsString("validationQuery"))));
    }

    @Test
    void openApiDocumentIsAccessibleAnonymously() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerUiIsAccessibleAnonymously() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerUiShortcutRedirectsAnonymously() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void businessEndpointRejectsAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }
}
