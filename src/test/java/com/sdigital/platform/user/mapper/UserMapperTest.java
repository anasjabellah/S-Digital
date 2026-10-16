package com.sdigital.platform.user.mapper;

import com.sdigital.platform.user.UserTestData;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UserMapper}.
 */
class UserMapperTest {

    private final UserMapper userMapper = new UserMapper();

    @Test
    void toResponseMapsExposedFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);

        UserResponse response = userMapper.toResponse(user);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.lastName()).isEqualTo("Doe");
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void toEntityMapsCreateRequestAndLeavesTechnicalFieldsUnset() {
        CreateUserRequest request = new CreateUserRequest("alice@example.com", "Alice", "Doe");

        User user = userMapper.toEntity(request);

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getFirstName()).isEqualTo("Alice");
        assertThat(user.getLastName()).isEqualTo("Doe");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getId()).isNull();
        assertThat(user.getKeycloakId()).isNull();
    }

    @Test
    void applyUpdateOnlyChangesModifiableFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        User user = UserTestData.user(id, "alice@example.com", "Alice", "Doe", "kc-1", createdAt, updatedAt);
        UpdateUserRequest request = new UpdateUserRequest("alice2@example.com", "Alicia", "Smith");

        userMapper.applyUpdate(user, request);

        assertThat(user.getEmail()).isEqualTo("alice2@example.com");
        assertThat(user.getFirstName()).isEqualTo("Alicia");
        assertThat(user.getLastName()).isEqualTo("Smith");
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getKeycloakId()).isEqualTo("kc-1");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
    }
}
