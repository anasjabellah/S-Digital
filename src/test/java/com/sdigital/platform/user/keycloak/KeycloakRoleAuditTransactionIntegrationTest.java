package com.sdigital.platform.user.keycloak;

import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Transactional integration tests for the persistent audit of Keycloak role changes,
 * against a real PostgreSQL 16 container.
 *
 * <p>These tests guard the transaction boundary of {@link KeycloakRoleAdminService}: the role
 * mutations run in a read-write transaction (a read-only one would make PostgreSQL reject the
 * INSERT and Hibernate skip the flush), so the audit entry is committed with the call and is
 * rolled back when the call fails after the audit write.</p>
 *
 * <p>Keycloak itself is stubbed: it is an external system and cannot participate in the
 * PostgreSQL transaction. The tests therefore assert the database side of the contract only,
 * which is the part this step owns.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "sdigital.keycloak.admin.server-url=http://keycloak.test",
        "sdigital.keycloak.admin.realm=test-realm",
        "sdigital.keycloak.admin.client-id=service-account",
        "sdigital.keycloak.admin.client-secret=service-secret",
        "sdigital.keycloak.admin.allowlist=MANAGER,AGENT"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditLogService.class, KeycloakRoleAdminService.class, RoleChangeAuditLogger.class})
@EnableConfigurationProperties(KeycloakAdminProperties.class)
@Testcontainers
class KeycloakRoleAuditTransactionIntegrationTest {

    private static final String TARGET_KEYCLOAK_UUID = "kc-target-uuid";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private KeycloakRoleAdminService keycloakRoleAdminService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private UUID targetUserId;
    private String targetKeycloakId;

    @BeforeEach
    void createTargetUser() {
        // the identifier is left to the JPA lifecycle: a fixture with an assigned id would
        // be merged as a detached entity and fail against a non-existing row.
        // The Keycloak id is unique per test because the fixture is really committed.
        targetKeycloakId = "kc-target-" + UUID.randomUUID();
        User target = new User("target-" + UUID.randomUUID() + "@example.com", "Target", "User");
        target.setKeycloakId(targetKeycloakId);
        targetUserId = userRepository.saveAndFlush(target).getId();

        given(keycloakAdminClient.findUserIdByInternalId(targetKeycloakId)).willReturn(TARGET_KEYCLOAK_UUID);
        given(keycloakAdminClient.isUserEnabled(TARGET_KEYCLOAK_UUID)).willReturn(true);
        given(keycloakAdminClient.addRealmRole(TARGET_KEYCLOAK_UUID, "MANAGER")).willReturn(true);
        given(keycloakAdminClient.removeRealmRole(TARGET_KEYCLOAK_UUID, "MANAGER")).willReturn(true);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void roleAssignmentPersistsItsAuditEntryInAReadWriteTransaction() {
        given(keycloakAdminClient.listRealmRoles(TARGET_KEYCLOAK_UUID)).willReturn(List.of("AGENT"));

        keycloakRoleAdminService.assignRole(targetUserId, "MANAGER");

        AuditLog entry = auditEventsOf("ROLE_ASSIGN").stream()
                .findFirst()
                .orElseThrow();

        assertThat(entry.getEntityType()).isEqualTo("USER_ROLE");
        assertThat(entry.getEntityId()).isEqualTo(targetUserId.toString());
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(entry.getDetails())
                .containsEntry("role", "MANAGER")
                .containsEntry("changed", true);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditEntryIsRolledBackWhenTheCallFailsAfterTheAuditWrite() {
        // the first read serves the idempotency check, the second one happens after the
        // audit write, inside the same transaction
        given(keycloakAdminClient.listRealmRoles(TARGET_KEYCLOAK_UUID))
                .willReturn(List.of("AGENT"))
                .willThrow(new IllegalStateException("keycloak unreachable"));

        assertThatThrownBy(() -> keycloakRoleAdminService.assignRole(targetUserId, "MANAGER"))
                .isInstanceOf(com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException.class);

        assertThat(auditEventsOf("ROLE_ASSIGN")).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deniedRoleChangePersistsADenialEntry() {
        given(keycloakAdminClient.listRealmRoles(TARGET_KEYCLOAK_UUID)).willReturn(List.of("AGENT"));

        assertThatThrownBy(() -> keycloakRoleAdminService.assignRole(targetUserId, "ADMIN"))
                .isInstanceOf(com.sdigital.platform.user.keycloak.exception.RoleChangeDeniedException.class);

        AuditLog entry = auditEventsOf("ROLE_ASSIGN").stream()
                .findFirst()
                .orElseThrow();

        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(entry.getDetails())
                .containsEntry("role", "ADMIN")
                .containsEntry("reason", "RESERVED_ROLE_DENIED");
    }

    private List<AuditLog> auditEventsOf(String action) {
        return auditLogRepository.findAll().stream()
                .filter(log -> action.equals(log.getAction()))
                .filter(log -> targetUserId.toString().equals(log.getEntityId()))
                .toList();
    }
}
