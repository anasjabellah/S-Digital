package com.sdigital.platform.user.service;

import com.sdigital.platform.user.UserTestData;
import com.sdigital.platform.user.dto.CreateUserRequest;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService}. The repository is mocked; the mapper is the real
 * implementation so mapping is exercised together with the business rules.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, new UserMapper());
    }

    @Test
    void createNormalizesEmailAndPersists() {
        CreateUserRequest request = new CreateUserRequest("  Alice@Example.COM ", "Alice", "Doe");
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
        CreateUserRequest request = new CreateUserRequest("alice@example.com", "Alice", "Doe");
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
    void updateAllowsUnchangedEmailOfSameUser() {
        UUID id = UUID.randomUUID();
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        UpdateUserRequest request = new UpdateUserRequest("  ALICE@example.com ", "Alicia", "Doe");
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
        UpdateUserRequest request = new UpdateUserRequest("bob@example.com", "Alice", "Doe");
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
        UpdateUserRequest request = new UpdateUserRequest("alice@example.com", "Alicia", "Doe");
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
    void getAllReturnsMappedPage() {
        User user = UserTestData.user(UUID.randomUUID(), "alice@example.com", "Alice", "Doe", null, Instant.now(), Instant.now());
        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        Page<UserResponse> page = userService.getAll(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("alice@example.com");
    }
}
