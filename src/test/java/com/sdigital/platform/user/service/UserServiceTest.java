package com.sdigital.platform.user.service;

import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.user.UserTestData;
import com.sdigital.platform.agency.AgencyTestData;
import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateCurrentUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserNotFoundException;
import com.sdigital.platform.user.mapper.UserMapper;
import com.sdigital.platform.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService}. The repository is mocked; the mapper is the real
 * implementation so mapping is exercised together with the business rules.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private UserRepository userRepository;

    @Mock
    private AgencyRepository agencyRepository;

    @Mock
    private FunctionRepository functionRepository;

    @Mock
    private UserIdentityService userIdentityService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentActorResolver currentActorResolver;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, new UserMapper(), agencyRepository, functionRepository,
                userIdentityService, auditLogService, currentActorResolver);
    }

    @Test
    void createNormalizesEmailAndPersists() {
        CreateUserRequest request = new CreateUserRequest("  Alice@Example.COM ", "Alice", "Doe", null, null);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.create(request);

        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository).existsByEmail("alice@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void createRejectsAlreadyUsedEmail() {
        CreateUserRequest request = new CreateUserRequest("alice@example.com", "Alice", "Doe", null, null);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getByIdThrowsWhenUserDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(id))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getByIdReturnsMappedUser() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        UserResponse response = userService.getById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void getCurrentUserReturnsMappedProfileOfResolvedIdentity() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);
        when(userIdentityService.resolveCurrentLocalUser()).thenReturn(Optional.of(user));

        UserResponse response = userService.getCurrentUser();

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.lastName()).isEqualTo("Doe");
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        verify(userIdentityService).resolveCurrentLocalUser();
        verify(userRepository, never()).findById(any(UUID.class));
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void getCurrentUserNeverFallsBackToRepositoryLookupByIdOrEmail() {
        UUID id = UUID.randomUUID();
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", Instant.now(), Instant.now());
        when(userIdentityService.resolveCurrentLocalUser()).thenReturn(Optional.of(user));

        userService.getCurrentUser();

        verify(userRepository, never()).findById(any(UUID.class));
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).findByKeycloakId(any());
    }

    @Test
    void getCurrentUserThrowsWhenIdentityIsNotLinked() {
        when(userIdentityService.resolveCurrentLocalUser()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser())
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).findById(any(UUID.class));
    }

    @Test
    void updateCurrentUserTrimsAndUpdatesNamesAndAuditsChangedFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);
        when(userIdentityService.resolveCurrentLocalUser()).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        UserResponse response = userService.updateCurrentUser(
                new UpdateCurrentUserRequest("  Alicia ", " Smith "));

        assertThat(response.firstName()).isEqualTo("Alicia");
        assertThat(response.lastName()).isEqualTo("Smith");
        verify(userRepository).save(user);
        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_PROFILE_UPDATED"), eq("USER"),
                eq(id.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("fields", List.of("firstName", "lastName"))));
    }

    @Test
    void updateCurrentUserNeverTouchesAdministrationFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);
        when(userIdentityService.resolveCurrentLocalUser()).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.updateCurrentUser(new UpdateCurrentUserRequest("Alicia", "Smith"));

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getKeycloakId()).isEqualTo("kc-1");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getAgency()).isNull();
        assertThat(user.getFunction()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).existsByEmailAndIdNot(any(), any());
    }

    @Test
    void updateCurrentUserWritesNoAuditEventWhenNamesAreUnchanged() {
        UUID id = UUID.randomUUID();
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        when(userIdentityService.resolveCurrentLocalUser()).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.updateCurrentUser(new UpdateCurrentUserRequest("Alice", "Doe"));

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void updateCurrentUserThrowsWhenIdentityIsNotLinked() {
        when(userIdentityService.resolveCurrentLocalUser()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateCurrentUser(new UpdateCurrentUserRequest("Alice", "Doe")))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).findById(any(UUID.class));
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void updateAllowsUnchangedEmailOfSameUser() {
        UUID id = UUID.randomUUID();
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        UpdateUserRequest request = new UpdateUserRequest("  ALICE@example.com ", "Alicia", "Doe", null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("alice@example.com", id)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.update(id, request);

        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.firstName()).isEqualTo("Alicia");
        verify(userRepository).existsByEmailAndIdNot("alice@example.com", id);
        verify(userRepository).save(user);
    }

    @Test
    void updateRejectsEmailUsedByAnotherUser() {
        UUID id = UUID.randomUUID();
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        UpdateUserRequest request = new UpdateUserRequest("bob@example.com", "Alice", "Doe", null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("bob@example.com", id)).thenReturn(true);

        assertThatThrownBy(() -> userService.update(id, request))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateDoesNotModifyProtectedFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);
        UpdateUserRequest request = new UpdateUserRequest("alice@example.com", "Alicia", "Doe", null, null);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("alice@example.com", id)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.update(id, request);

        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getKeycloakId()).isEqualTo("kc-1");
        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void deactivateSetsStatusInactiveWithoutDeleting() {
        UUID id = UUID.randomUUID();
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.deactivate(id);

        assertThat(response.status()).isEqualTo(UserStatus.INACTIVE);
        verify(userRepository).save(user);
        verify(userRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void activateRestoresStatusActiveAndPreservesOtherFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);
        user.setStatus(UserStatus.INACTIVE);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.activate(id);

        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.lastName()).isEqualTo("Doe");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        assertThat(user.getKeycloakId()).isEqualTo("kc-1");
        verify(userRepository).save(user);
        verify(userRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void activateThrowsWhenUserDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.activate(id))
                .isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getAllReturnsMappedPage() {
        User user = UserTestData.user(UUID.randomUUID(), "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        Page<UserResponse> page = userService.getAll(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("alice@example.com");
    }

    @Test
    void getAllWithoutFiltersKeepsTheUnfilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        userService.getAll(null, null, pageable);

        verify(userRepository).findAll(pageable);
        verify(userRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllTreatsBlankSearchAsAbsent() {
        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        userService.getAll("   ", null, pageable);

        verify(userRepository).findAll(pageable);
        verify(userRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllWithSearchUsesTheFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = UserTestData.user(UUID.randomUUID(), "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        Page<UserResponse> page = userService.getAll("alice", null, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("alice@example.com");
        verify(userRepository, never()).findAll(pageable);
    }

    @Test
    void getAllWithStatusOnlyUsesTheFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        userService.getAll(null, UserStatus.INACTIVE, pageable);

        verify(userRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void getAllCombinesSearchAndStatus() {
        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        userService.getAll("  doe ", UserStatus.ACTIVE, pageable);

        verify(userRepository).findAll(any(Specification.class), eq(pageable));
        verify(userRepository, never()).findAll(pageable);
    }

    // --- persistent audit ----------------------------------------------------------------------

    @Test
    void createProvisionedPersistsKeycloakIdAndAuditsLinkFlag() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            UserTestData.setId(saved, userId);
            return saved;
        });
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        UserResponse response = userService.createProvisioned(
                new CreateUserRequest("  ADA@example.com ", "Ada", "Lovelace", null, null),
                "kc-internal-1");

        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getKeycloakId()).isEqualTo("kc-internal-1");
        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_CREATED"), eq("USER"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("status", "ACTIVE", "keycloakLinked", true)));
    }

    @Test
    void createProvisionedRejectsDuplicateEmailBeforeSaving() {
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createProvisioned(
                new CreateUserRequest("ada@example.com", "Ada", "Lovelace", null, null),
                "kc-internal-1"))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void validateAssignmentsRejectsMissingAgencyWithoutWriting() {
        UUID agencyId = UUID.randomUUID();
        when(agencyRepository.findById(agencyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.validateAssignments(agencyId, null))
                .isInstanceOf(com.sdigital.platform.user.exception.UserAgencyNotFoundException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void createProvisionedRejectsBlankKeycloakId() {
        assertThatThrownBy(() -> userService.createProvisioned(
                new CreateUserRequest("ada@example.com", "Ada", "Lovelace", null, null),
                "   "))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void createAuditsUserCreatedWithoutPersonalData() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            UserTestData.setId(saved, userId);
            return saved;
        });
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        userService.create(new CreateUserRequest("alice@example.com", "Alice", "Doe", null, null));

        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_CREATED"), eq("USER"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("status", "ACTIVE")));
    }

    @Test
    void updateAuditsUserUpdatedWithoutPersonalData() {
        UUID userId = UUID.randomUUID();
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("alice@example.com", userId)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        userService.update(userId, new UpdateUserRequest("alice@example.com", "Alicia", "Doe", null, null));

        // only the field that really changed is listed, never the e-mail address itself
        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_UPDATED"), eq("USER"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("fields", List.of("firstName"))));
    }

    @Test
    void updateThatChangesNothingWritesNoAuditEvent() {
        UUID userId = UUID.randomUUID();
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("alice@example.com", userId)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.update(userId, new UpdateUserRequest("alice@example.com", "Alice", "Doe", null, null));

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void updateAuditsAssignmentChangeSeparatelyWhenAgencyChanges() {
        UUID userId = UUID.randomUUID();
        UUID previousAgencyId = UUID.randomUUID();
        UUID newAgencyId = UUID.randomUUID();

        Agency previousAgency = agency(previousAgencyId);
        Agency newAgency = agency(newAgencyId);
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        user.setAgency(previousAgency);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("alice@example.com", userId)).thenReturn(false);
        when(agencyRepository.findById(newAgencyId)).thenReturn(Optional.of(newAgency));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        userService.update(userId, new UpdateUserRequest("alice@example.com", "Alice", "Doe", newAgencyId, null));

        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_ASSIGNMENT_CHANGED"), eq("USER_ASSIGNMENT"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("previousAgencyId", previousAgencyId.toString(), "agencyId", newAgencyId.toString())));
        // no other field changed: the assignment is the only audited event
        verify(auditLogService, never()).record(any(), eq("USER_UPDATED"), any(), any(), any(), anyMap());
    }

    @Test
    void updateWritesNoAssignmentEventWhenAssignmentsAreUnchanged() {
        UUID userId = UUID.randomUUID();
        UUID agencyId = UUID.randomUUID();
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        user.setAgency(agency(agencyId));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("alice@example.com", userId)).thenReturn(false);
        when(agencyRepository.findById(agencyId)).thenReturn(Optional.of(agency(agencyId)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.update(userId, new UpdateUserRequest("alice@example.com", "Alice", "Doe", agencyId, null));

        verify(auditLogService, never()).record(any(), eq("USER_ASSIGNMENT_CHANGED"), any(), any(), any(), anyMap());
        // nothing changed at all: no user event either
        verify(auditLogService, never()).record(any(), eq("USER_UPDATED"), any(), any(), any(), anyMap());
    }

    @Test
    void updateThatChangesAFieldAndTheAssignmentWritesBothEvents() {
        UUID userId = UUID.randomUUID();
        UUID previousAgencyId = UUID.randomUUID();
        UUID newAgencyId = UUID.randomUUID();

        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        user.setAgency(agency(previousAgencyId));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("alice@example.com", userId)).thenReturn(false);
        when(agencyRepository.findById(newAgencyId)).thenReturn(Optional.of(agency(newAgencyId)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        userService.update(userId, new UpdateUserRequest("alice@example.com", "Alicia", "Doe", newAgencyId, null));

        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_ASSIGNMENT_CHANGED"), eq("USER_ASSIGNMENT"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS), anyMap());
        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_UPDATED"), eq("USER"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("fields", List.of("firstName"))));
    }

    @Test
    void deactivateAuditsUserDeactivatedWithStatusTransition() {
        UUID userId = UUID.randomUUID();
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        userService.deactivate(userId);

        verify(auditLogService).record(eq(ACTOR_ID), eq("USER_DEACTIVATED"), eq("USER"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("previousStatus", "ACTIVE", "status", "INACTIVE")));
    }

    @Test
    void activateAuditsUserActivatedWithStatusTransition() {
        UUID userId = UUID.randomUUID();
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        user.setStatus(UserStatus.INACTIVE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(null);

        userService.activate(userId);

        verify(auditLogService).record(eq(null), eq("USER_ACTIVATED"), eq("USER"),
                eq(userId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("previousStatus", "INACTIVE", "status", "ACTIVE")));
    }

    @Test
    void deactivateOnAlreadyInactiveUserWritesNoAuditEvent() {
        UUID userId = UUID.randomUUID();
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        user.setStatus(UserStatus.INACTIVE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.deactivate(userId);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void activateOnAlreadyActiveUserWritesNoAuditEvent() {
        UUID userId = UUID.randomUUID();
        User user = UserTestData.user(userId, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.activate(userId);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void failedMutationWritesNoAuditEvent() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(new CreateUserRequest("alice@example.com", "Alice", "Doe", null, null)))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void readOperationsWriteNoAuditEvent() {
        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        userService.getAll(pageable);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }

    private static Agency agency(UUID id) {
        return AgencyTestData.agency(id, "AG-" + id.toString().substring(0, 8), "Agency", Instant.now(), Instant.now());
    }
}
