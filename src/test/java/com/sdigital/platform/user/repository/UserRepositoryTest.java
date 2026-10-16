package com.sdigital.platform.user.repository;

import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link UserRepository} running against a real PostgreSQL 16
 * container. Flyway runs the migrations and Hibernate validates the schema, so the
 * tests also cover the {@code V1__create_user.sql} migration and its constraints.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesUserWithGeneratedIdDefaultsAndTimestamps() {
        User saved = userRepository.saveAndFlush(new User("alice@example.com", "Alice", "Doe"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsUserByEmail() {
        userRepository.saveAndFlush(new User("bob@example.com", "Bob", "Marley"));

        Optional<User> found = userRepository.findByEmail("bob@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getFirstName()).isEqualTo("Bob");
        assertThat(userRepository.existsByEmail("bob@example.com")).isTrue();
    }

    @Test
    void rejectsDuplicateEmail() {
        userRepository.saveAndFlush(new User("duplicate@example.com", "First", "User"));

        assertThatThrownBy(() ->
                userRepository.saveAndFlush(new User("duplicate@example.com", "Second", "User")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsMultipleUsersWithoutKeycloakId() {
        userRepository.saveAndFlush(new User("no-keycloak-1@example.com", "One", "None"));
        userRepository.saveAndFlush(new User("no-keycloak-2@example.com", "Two", "None"));

        assertThat(userRepository.count()).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateKeycloakId() {
        User first = new User("kc-1@example.com", "One", "Kc");
        first.setKeycloakId("keycloak-user-123");
        userRepository.saveAndFlush(first);

        User second = new User("kc-2@example.com", "Two", "Kc");
        second.setKeycloakId("keycloak-user-123");

        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
