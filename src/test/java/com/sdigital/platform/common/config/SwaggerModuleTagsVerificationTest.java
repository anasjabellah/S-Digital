package com.sdigital.platform.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Focused OpenAPI verification: confirms the generated document groups every existing
 * endpoint under the business-module tags, keeps the bearer-jwt scheme, and does not
 * expose obsolete or fake modules.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SwaggerModuleTagsVerificationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    void tagsAreOrganizedByBusinessModule() {
        String json = rest.getForObject("http://localhost:" + port + "/v3/api-docs", String.class);
        assertThat(json).isNotNull();

        // Expected business-module tags, in frontend navigation order.
        assertThat(json).contains("\"Tableau de bord\"");
        assertThat(json).contains("\"Utilisateurs\"");
        assertThat(json).contains("\"Référentiels\"");
        assertThat(json).contains("\"Rapports\"");
        assertThat(json).contains("\"Notifications\"");
        assertThat(json).contains("\"Modèles d'emails\"");
        assertThat(json).contains("\"Journal d'audit\"");

        // Obsolete English tags must not remain.
        assertThat(json).doesNotContain("\"Agencies\"");
        assertThat(json).doesNotContain("\"Functions\"");
        assertThat(json).doesNotContain("\"User roles\"");
        assertThat(json).doesNotContain("\"Users\"");

        // Existing paths remain present.
        assertThat(json).contains("/api/v1/dashboard/summary");
        assertThat(json).contains("/api/v1/audit-logs");
        assertThat(json).contains("/api/v1/reports");
        assertThat(json).contains("/api/v1/notifications");
        assertThat(json).contains("/api/v1/notifications/unread");
        assertThat(json).contains("/api/v1/notifications/unread/count");
        assertThat(json).contains("/api/v1/notifications/read/all");
        assertThat(json).contains("/api/v1/email-templates");
        assertThat(json).contains("/api/v1/users/");
        assertThat(json).contains("/api/v1/agencies/");
        assertThat(json).contains("/api/v1/functions/");

        // Bearer JWT security scheme preserved.
        assertThat(json).contains("bearer-jwt");

        // No fake modules.
        assertThat(json).doesNotContain("/rapports");
    }
}
