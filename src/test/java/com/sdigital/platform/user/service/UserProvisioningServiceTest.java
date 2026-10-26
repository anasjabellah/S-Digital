package com.sdigital.platform.user.service;

import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserAgencyNotFoundException;
import com.sdigital.platform.user.exception.UserProvisioningException;
import com.sdigital.platform.user.keycloak.KeycloakAdminClient;
import com.sdigital.platform.user.keycloak.KeycloakAdminProperties;
import com.sdigital.platform.user.keycloak.exception.KeycloakIdentityAlreadyExistsException;
import com.sdigital.platform.user.keycloak.exception.KeycloakIntegrationException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUpstreamException;
import com.sdigital.platform.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserProvisioningService}.
 *
 * <p>The Keycloak client, the local persistence service and the repository are mocked so
 * every success and failure branch is deterministic. No real Keycloak is contacted.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserProvisioningServiceTest {

    private static final String EMAIL = "ada@example.com";
    private static final String KEYCLOAK_ID = "kc-internal-1";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private KeycloakAdminProperties properties;

    private UserProvisioningService provisioningService;

    @BeforeEach
    void setUp() {
        lenient().when(properties.isConfigured()).thenReturn(true);
        provisioningService = new UserProvisioningService(
                userRepository, userService, keycloakAdminClient, properties);
    }

    @Test
    void provisionCreatesKeycloakIdentityAndLinksInternalId() {
        UserResponse expected = sample();
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn(KEYCLOAK_ID);
        when(userService.createProvisioned(any(CreateUserRequest.class), eq(KEYCLOAK_ID)))
                .thenReturn(expected);

        UserResponse result = provisioningService.provision(request());

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<CreateUserRequest> captor = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(userService).createProvisioned(captor.capture(), eq(KEYCLOAK_ID));
        assertThat(captor.getValue().email()).isEqualTo(EMAIL);
        // The internal Keycloak id is passed to persistence, never the username.
        assertThat(captor.getValue().email()).isNotEqualTo(KEYCLOAK_ID);
        // No role is granted and no existing identity is deleted on success.
        verify(keycloakAdminClient, never()).addRealmRole(any(), any());
        verify(keycloakAdminClient, never()).removeRealmRole(any(), any());
        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    void provisionRejectsDuplicateLocalEmailBeforeCallingKeycloak() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verifyNoInteractions(keycloakAdminClient);
        verify(userService, never()).createProvisioned(any(), any());
    }

    @Test
    void provisionValidatesAssignmentsBeforeCallingKeycloak() {
        UUID agencyId = UUID.randomUUID();
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        doThrow(new UserAgencyNotFoundException(agencyId))
                .when(userService).validateAssignments(agencyId, null);

        assertThatThrownBy(() -> provisioningService.provision(
                new CreateUserRequest(EMAIL, "Ada", "Lovelace", agencyId, null)))
                .isInstanceOf(UserAgencyNotFoundException.class);

        verifyNoInteractions(keycloakAdminClient);
        verify(userService, never()).createProvisioned(any(), any());
    }

    @Test
    void provisionRejectsExistingKeycloakIdentityWithoutCreatingAnother() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn("kc-existing");

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(KeycloakIdentityAlreadyExistsException.class);

        verify(keycloakAdminClient, never()).createUser(any(), any(), any(), any(), anyBoolean());
        verify(keycloakAdminClient, never()).deleteUser(any());
        verify(userService, never()).createProvisioned(any(), any());
    }

    @Test
    void provisionMapsKeycloakCreationFailureToUnavailable() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenThrow(new KeycloakUpstreamException(
                        KeycloakUpstreamException.Kind.SERVER_ERROR, 500, "upstream detail"));

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(KeycloakUnavailableException.class)
                .hasMessageNotContaining("upstream detail");

        verify(userService, never()).createProvisioned(any(), any());
        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    void provisionMapsKeycloakAuthenticationFailureToIntegrationError() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenThrow(new KeycloakUpstreamException(
                        KeycloakUpstreamException.Kind.UNAUTHENTICATED, 401, "secret-token"));

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(KeycloakIntegrationException.class)
                .hasMessageNotContaining("secret-token");

        verify(userService, never()).createProvisioned(any(), any());
    }

    @Test
    void provisionMapsKeycloakConflictToExistingIdentity() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenThrow(new KeycloakUpstreamException(
                        KeycloakUpstreamException.Kind.CONFLICT, 409, "conflict"));

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(KeycloakIdentityAlreadyExistsException.class);

        verify(userService, never()).createProvisioned(any(), any());
        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    void provisionCompensatesWhenLocalPersistenceFailsAfterRemoteCreation() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn(KEYCLOAK_ID);
        when(userService.createProvisioned(any(CreateUserRequest.class), eq(KEYCLOAK_ID)))
                .thenThrow(new DataIntegrityViolationException("database detail"));

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(UserProvisioningException.class)
                .hasMessageNotContaining(EMAIL)
                .hasMessageNotContaining(KEYCLOAK_ID)
                .satisfies(ex -> assertThat(((UserProvisioningException) ex).getReason())
                        .isEqualTo(UserProvisioningException.Reason.LOCAL_PERSISTENCE_FAILED));

        verify(keycloakAdminClient).deleteUser(KEYCLOAK_ID);
    }

    @Test
    void provisionRethrowsUniqueKeycloakLinkViolationSoTheHttpLayerMapsItTo409() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn(KEYCLOAK_ID);
        when(userService.createProvisioned(any(CreateUserRequest.class), eq(KEYCLOAK_ID)))
                .thenThrow(uniqueKeycloakLinkViolation());

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(keycloakAdminClient).deleteUser(KEYCLOAK_ID);
    }

    @Test
    void provisionCompensatesAndReturnsConflictWhenLocalEmailRaceIsDetected() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false, true);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn(KEYCLOAK_ID);
        when(userService.createProvisioned(any(CreateUserRequest.class), eq(KEYCLOAK_ID)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(keycloakAdminClient).deleteUser(KEYCLOAK_ID);
    }

    @Test
    void provisionCompensatesWhenAssignmentsAreRejectedAfterRemoteCreation() {
        UUID agencyId = UUID.randomUUID();
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn(KEYCLOAK_ID);
        when(userService.createProvisioned(any(CreateUserRequest.class), eq(KEYCLOAK_ID)))
                .thenThrow(new UserAgencyNotFoundException(agencyId));

        assertThatThrownBy(() -> provisioningService.provision(
                new CreateUserRequest(EMAIL, "Ada", "Lovelace", agencyId, null)))
                .isInstanceOf(UserAgencyNotFoundException.class);

        verify(keycloakAdminClient).deleteUser(KEYCLOAK_ID);
    }

    @Test
    void provisionStillReportsTheOriginalFailureWhenCompensationFails() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn(KEYCLOAK_ID);
        when(userService.createProvisioned(any(CreateUserRequest.class), eq(KEYCLOAK_ID)))
                .thenThrow(new DataIntegrityViolationException("database detail"));
        doThrow(new KeycloakUnavailableException("delete failed"))
                .when(keycloakAdminClient).deleteUser(KEYCLOAK_ID);

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(UserProvisioningException.class);

        verify(keycloakAdminClient).deleteUser(KEYCLOAK_ID);
    }

    @Test
    void provisionRejectsMissingInternalIdWithoutCompensation() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn("   ");

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(UserProvisioningException.class)
                .satisfies(ex -> assertThat(((UserProvisioningException) ex).getReason())
                        .isEqualTo(UserProvisioningException.Reason.KEYCLOAK_USER_ID_MISSING));

        verify(keycloakAdminClient, never()).deleteUser(any());
        verify(userService, never()).createProvisioned(any(), any());
    }

    @Test
    void provisionCompensatesAnInternalIdTooLongForTheLocalColumn() {
        String tooLong = "x".repeat(256);
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(keycloakAdminClient.findUserIdByUsername(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, EMAIL, "Ada", "Lovelace", true))
                .thenReturn(tooLong);

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(UserProvisioningException.class)
                .satisfies(ex -> assertThat(((UserProvisioningException) ex).getReason())
                        .isEqualTo(UserProvisioningException.Reason.KEYCLOAK_USER_ID_INVALID));

        verify(keycloakAdminClient).deleteUser(tooLong);
        verify(userService, never()).createProvisioned(any(), any());
    }

    @Test
    void provisionFailsClosedWhenKeycloakIsNotConfigured() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(properties.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> provisioningService.provision(request()))
                .isInstanceOf(KeycloakUnavailableException.class);

        verifyNoInteractions(keycloakAdminClient);
        verify(userService, never()).createProvisioned(any(), any());
    }

    private static CreateUserRequest request() {
        return new CreateUserRequest("  ADA@Example.COM ", "Ada", "Lovelace", null, null);
    }

    private static DataIntegrityViolationException uniqueKeycloakLinkViolation() {
        SQLException sqlException = new SQLException(
                "duplicate key value violates unique constraint \"uk_app_user_keycloak_id\"", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sqlException,
                        "uk_app_user_keycloak_id"));
    }

    private static UserResponse sample() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        return new UserResponse(UUID.randomUUID(), EMAIL, "Ada", "Lovelace",
                UserStatus.ACTIVE, null, null, now, now);
    }
}
