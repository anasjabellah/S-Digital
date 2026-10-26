package com.sdigital.platform.user.keycloak;

import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.user.dto.UserRolesResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.exception.UserNotFoundException;
import com.sdigital.platform.user.keycloak.exception.KeycloakIntegrationException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUpstreamException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUserNotFoundException;
import com.sdigital.platform.user.keycloak.exception.RoleChangeDeniedException;
import com.sdigital.platform.user.keycloak.exception.RoleNotAllowedException;
import com.sdigital.platform.user.keycloak.exception.UserNotLinkedToKeycloakException;
import com.sdigital.platform.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KeycloakRoleAdminService}, covering the provisional security rules:
 * allowlist, reserved ADMIN role, self-modification, unlinked users, disabled accounts and
 * fail-closed configuration.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakRoleAdminServiceTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TARGET_SUB = "target-sub";
    private static final String TARGET_KC_ID = "kc-target";

    @Mock
    private UserRepository userRepository;
    @Mock
    private KeycloakAdminClient keycloakAdminClient;
    @Mock
    private RoleChangeAuditLogger auditLogger;
    @Mock
    private AuditLogService auditLogService;

    private KeycloakAdminProperties properties;
    private KeycloakRoleAdminService service;

    @BeforeEach
    void setUp() {
        properties = new KeycloakAdminProperties();
        properties.setServerUrl("http://keycloak.test");
        properties.setRealm("s-digital");
        properties.setClientId("backend-admin");
        properties.setClientSecret("s3cr3t");
        properties.setAllowlist(List.of("MANAGER", "USER"));
        service = new KeycloakRoleAdminService(userRepository, keycloakAdminClient, properties, auditLogger, auditLogService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String sub) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private User linkedTarget() {
        User user = new User("target@example.com", "Tar", "Get");
        user.setKeycloakId(TARGET_SUB);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    // --- GET -----------------------------------------------------------------------------------

    @Test
    void getRolesReturnsOnlyAllowlistedRoles() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID)).thenReturn(List.of("ADMIN", "MANAGER", "OTHER"));

        UserRolesResponse response = service.getRoles(TARGET_ID);

        assertThat(response.roles()).extracting("name").containsExactly("MANAGER");
    }

    @Test
    void getRolesThrowsWhenLocalUserMissing() {
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRoles(TARGET_ID)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getRolesThrowsWhenUserNotLinked() {
        User unlinked = new User("target@example.com", "Tar", "Get");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(unlinked));

        assertThatThrownBy(() -> service.getRoles(TARGET_ID))
                .isInstanceOf(UserNotLinkedToKeycloakException.class);
    }

    @Test
    void getRolesThrowsWhenKeycloakUserMissing() {
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(null);

        assertThatThrownBy(() -> service.getRoles(TARGET_ID))
                .isInstanceOf(KeycloakUserNotFoundException.class);
    }

    @Test
    void getRolesFailsClosedWhenNotConfigured() {
        properties.setClientSecret("");

        assertThatThrownBy(() -> service.getRoles(TARGET_ID))
                .isInstanceOf(KeycloakUnavailableException.class);
    }

    // --- assign --------------------------------------------------------------------------------

    @Test
    void assignRoleSucceedsForAllowlistedRole() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        // pre-state: role not assigned; post-state: role assigned (returned by getRoles)
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID))
                .thenReturn(List.of())
                .thenReturn(List.of("MANAGER"));
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER")).thenReturn(true);

        UserRolesResponse response = service.assignRole(TARGET_ID, "MANAGER");

        assertThat(response.roles()).extracting("name").containsExactly("MANAGER");
        verify(auditLogger).roleChanged(any(), eq("admin-sub"), eq(TARGET_ID), eq(TARGET_SUB),
                eq("MANAGER"), eq("ASSIGN"));
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(null), eq("ROLE_ASSIGN"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.SUCCESS), details.capture());
        assertThat(details.getValue()).containsEntry("role", "MANAGER").containsEntry("changed", true);
    }

    @Test
    void assignRoleRejectsAdminRole() {
        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "ADMIN"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(keycloakAdminClient, never()).addRealmRole(anyString(), anyString());
    }

    @Test
    void deniedMutationIsAuditedOutsideTheCallerTransactionSoItSurvivesTheRollback() {
        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "ADMIN"))
                .isInstanceOf(RoleChangeDeniedException.class);

        // the refusal ends with an exception that rolls back the caller transaction:
        // the denial must therefore be written in an independent transaction
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
        verify(auditLogService).recordIndependent(any(), eq("ROLE_ASSIGN"), eq("USER_ROLE"),
                eq(TARGET_ID.toString()), eq(AuditOutcome.DENIED), any(Map.class));
    }

    @Test
    void assignRoleRejectsRoleOutsideAllowlist() {
        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "SUPERUSER"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(keycloakAdminClient, never()).addRealmRole(anyString(), anyString());
    }

    @Test
    void assignRoleFailsClosedWhenAllowlistEmpty() {
        properties.setAllowlist(List.of());

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(keycloakAdminClient, never()).addRealmRole(anyString(), anyString());
    }

    @Test
    void assignRoleRejectsSelfModification() {
        User self = linkedTarget();
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(self));
        when(userRepository.findByKeycloakId(TARGET_SUB)).thenReturn(Optional.of(self));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        authenticateAs(TARGET_SUB);

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(keycloakAdminClient, never()).addRealmRole(anyString(), anyString());
        verify(auditLogger).roleChangeDenied(any(), eq(TARGET_ID), eq("MANAGER"), eq("ASSIGN"),
                eq("SELF_MODIFICATION_DENIED"));
        verify(auditLogService).recordIndependent(any(), eq("ROLE_ASSIGN"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.DENIED), any(Map.class));
    }

    @Test
    void assignRoleRejectsUnknownRoleInKeycloak() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER")).thenReturn(false);

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    // --- remove --------------------------------------------------------------------------------

    @Test
    void removeRoleSucceedsForAllowlistedRole() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        // pre-state: role present; post-state: role removed
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID))
                .thenReturn(List.of("MANAGER"))
                .thenReturn(List.of());
        when(keycloakAdminClient.removeRealmRole(TARGET_KC_ID, "MANAGER")).thenReturn(true);

        UserRolesResponse response = service.removeRole(TARGET_ID, "MANAGER");

        assertThat(response.roles()).isEmpty();
        verify(auditLogger).roleChanged(any(), eq("admin-sub"), eq(TARGET_ID), eq(TARGET_SUB),
                eq("MANAGER"), eq("REMOVE"));
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(null), eq("ROLE_REMOVE"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.SUCCESS), details.capture());
        assertThat(details.getValue()).containsEntry("role", "MANAGER").containsEntry("changed", true);
    }

    @Test
    void removeRoleRejectsAdminRole() {
        assertThatThrownBy(() -> service.removeRole(TARGET_ID, "ADMIN"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(keycloakAdminClient, never()).removeRealmRole(anyString(), anyString());
    }

    // --- disabled Keycloak account (H1) --------------------------------------------------------

    @Test
    void assignRoleRejectsDisabledKeycloakAccountWithoutMutating() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(keycloakAdminClient, never()).addRealmRole(anyString(), anyString());
    }

    @Test
    void removeRoleRejectsDisabledKeycloakAccountWithoutMutating() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.removeRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(keycloakAdminClient, never()).removeRealmRole(anyString(), anyString());
    }

    @Test
    void assignRoleProceedsWhenKeycloakAccountEnabled() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER")).thenReturn(true);
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID)).thenReturn(List.of("MANAGER"));

        UserRolesResponse response = service.assignRole(TARGET_ID, "MANAGER");

        assertThat(response.roles()).extracting("name").containsExactly("MANAGER");
    }

    // --- failure translation -------------------------------------------------------------------

    @Test
    void keycloakFailureBecomesUnavailable() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.getRoles(TARGET_ID))
                .isInstanceOf(KeycloakUnavailableException.class)
                .hasMessageNotContaining("boom");
    }

    // --- M3: mutation failures are translated, never left as raw 500 ---------------------------

    @Test
    void assignRoleMutationServerErrorBecomesUnavailable() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER")).thenThrow(
                new KeycloakUpstreamException(KeycloakUpstreamException.Kind.SERVER_ERROR, 500, "kc"));

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(KeycloakUnavailableException.class)
                .hasMessageNotContaining("500");
    }

    @Test
    void removeRoleMutationNetworkFailureBecomesUnavailable() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        when(keycloakAdminClient.removeRealmRole(TARGET_KC_ID, "MANAGER"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.removeRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(KeycloakUnavailableException.class)
                .hasMessageNotContaining("timeout");
    }

    @Test
    void mutationForbiddenUpstreamBecomesIntegrationFailureNotUnavailable() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER")).thenThrow(
                new KeycloakUpstreamException(KeycloakUpstreamException.Kind.FORBIDDEN, 403, "kc"));

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(KeycloakIntegrationException.class)
                .hasMessageNotContaining("403");
    }

    // --- idempotency (proven Keycloak behaviour: add-present and remove-absent both 2xx) --------

    @Test
    void assignRoleIsIdempotentWhenRoleAlreadyPresent() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        // pre-state: already present
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID))
                .thenReturn(List.of("MANAGER"))
                .thenReturn(List.of("MANAGER"));
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER")).thenReturn(true);

        UserRolesResponse response = service.assignRole(TARGET_ID, "MANAGER");

        assertThat(response.roles()).extracting("name").containsExactly("MANAGER");
        verify(keycloakAdminClient, times(1)).addRealmRole(TARGET_KC_ID, "MANAGER");
        // first listRealmRoles call is pre-state read; second is the getRoles() call at the end
        verify(keycloakAdminClient, times(2)).listRealmRoles(TARGET_KC_ID);
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(null), eq("ROLE_ASSIGN"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.NO_CHANGE), details.capture());
        assertThat(details.getValue()).containsEntry("changed", false);
    }

    @Test
    void removeRoleIsIdempotentWhenRoleAbsent() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        // pre-state: absent
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(keycloakAdminClient.removeRealmRole(TARGET_KC_ID, "MANAGER")).thenReturn(true);

        UserRolesResponse response = service.removeRole(TARGET_ID, "MANAGER");

        assertThat(response.roles()).isEmpty();
        verify(keycloakAdminClient, times(1)).removeRealmRole(anyString(), anyString());
        verify(auditLogService).record(eq(null), eq("ROLE_REMOVE"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.NO_CHANGE), any(Map.class));
    }

    @Test
    void mutationIsNeverRetriedOnFailure() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(KeycloakUnavailableException.class);
        verify(keycloakAdminClient, times(1)).addRealmRole(TARGET_KC_ID, "MANAGER");
    }

    // --- audit of refusals (each refusal logged exactly once) -----------------------------------

    @Test
    void assignRoleAuditsAdminRoleRefusal() {
        authenticateAs("admin-sub");

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "ADMIN"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(auditLogger, times(1)).roleChangeDenied(any(), eq(TARGET_ID), eq("ADMIN"),
                eq("ASSIGN"), eq("RESERVED_ROLE_DENIED"));
        verify(auditLogService).recordIndependent(eq(null), eq("ROLE_ASSIGN"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.DENIED), any(Map.class));
    }

    @Test
    void assignRoleAuditsNotAllowlistedRefusal() {
        authenticateAs("admin-sub");

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "SUPERUSER"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(auditLogger, times(1)).roleChangeDenied(any(), eq(TARGET_ID), eq("SUPERUSER"),
                eq("ASSIGN"), eq("NOT_ALLOWLISTED"));
        verify(auditLogService).recordIndependent(eq(null), eq("ROLE_ASSIGN"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.DENIED), any(Map.class));
    }

    @Test
    void assignRoleAuditsDisabledAccountRefusal() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.assignRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(RoleChangeDeniedException.class);
        verify(auditLogger, times(1)).roleChangeDenied(any(), eq(TARGET_ID), eq("MANAGER"),
                eq("ASSIGN"), eq("KEYCLOAK_ACCOUNT_DISABLED"));
        verify(auditLogService).recordIndependent(eq(null), eq("ROLE_ASSIGN"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.DENIED), any(Map.class));
    }

    @Test
    void removeRoleAuditsUnlinkedUserRefusal() {
        authenticateAs("admin-sub");
        User unlinked = new User("target@example.com", "Tar", "Get");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(unlinked));

        assertThatThrownBy(() -> service.removeRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(UserNotLinkedToKeycloakException.class);
        verify(auditLogger, times(1)).roleChangeDenied(any(), eq(TARGET_ID), eq("MANAGER"),
                eq("REMOVE"), eq("USER_NOT_LINKED_TO_KEYCLOAK"));
        verify(auditLogService).recordIndependent(eq(null), eq("ROLE_REMOVE"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.DENIED), any(Map.class));
    }

    @Test
    void removeRoleAuditsKeycloakUserNotFoundRefusal() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(null);

        assertThatThrownBy(() -> service.removeRole(TARGET_ID, "MANAGER"))
                .isInstanceOf(KeycloakUserNotFoundException.class);
        verify(auditLogger, times(1)).roleChangeDenied(any(), eq(TARGET_ID), eq("MANAGER"),
                eq("REMOVE"), eq("KEYCLOAK_USER_NOT_FOUND"));
        verify(auditLogService).recordIndependent(eq(null), eq("ROLE_REMOVE"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.DENIED), any(Map.class));
    }

    @Test
    void successfulMutationAuditsChangeAndNoDenial() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.isUserEnabled(TARGET_KC_ID)).thenReturn(true);
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID))
                .thenReturn(List.of())
                .thenReturn(List.of("MANAGER"));
        when(keycloakAdminClient.addRealmRole(TARGET_KC_ID, "MANAGER")).thenReturn(true);

        service.assignRole(TARGET_ID, "MANAGER");

        verify(auditLogger).roleChanged(any(), eq("admin-sub"), eq(TARGET_ID), eq(TARGET_SUB),
                eq("MANAGER"), eq("ASSIGN"));
        verify(auditLogger, never()).roleChangeDenied(any(), any(), any(), any(), any());
        verify(auditLogService).record(any(), eq("ROLE_ASSIGN"), eq("USER_ROLE"), eq(TARGET_ID.toString()),
                eq(AuditOutcome.SUCCESS), any(Map.class));
    }

    // --- GET /roles contract --------------------------------------------------------------------

    @Test
    void getRolesReportsNoRolesWhenNoneAssigned() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID)).thenReturn(List.of());

        UserRolesResponse response = service.getRoles(TARGET_ID);

        assertThat(response.roles()).isEmpty();
        assertThat(response.hasUnmanageableRoles()).isFalse();
    }

    @Test
    void getRolesReportsManageableRolesWithoutUnmanageableFlag() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID)).thenReturn(List.of("MANAGER", "USER"));

        UserRolesResponse response = service.getRoles(TARGET_ID);

        assertThat(response.roles()).extracting("name").containsExactlyInAnyOrder("MANAGER", "USER");
        assertThat(response.hasUnmanageableRoles()).isFalse();
    }

    @Test
    void getRolesSignalsUnmanageableRolesWithoutLeakingTheirNames() {
        authenticateAs("admin-sub");
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(linkedTarget()));
        when(keycloakAdminClient.findUserIdByInternalId(TARGET_SUB)).thenReturn(TARGET_KC_ID);
        when(keycloakAdminClient.listRealmRoles(TARGET_KC_ID)).thenReturn(List.of("ADMIN", "OTHER", "MANAGER"));

        UserRolesResponse response = service.getRoles(TARGET_ID);

        assertThat(response.roles()).extracting("name").containsExactly("MANAGER");
        assertThat(response.hasUnmanageableRoles()).isTrue();
    }
}
