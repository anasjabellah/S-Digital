package com.sdigital.platform.user.repository;

import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.PropertyReferenceException;
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

    // --- Multicriteria filtering (UserSpecifications) -----------------------------------------

    @Test
    void searchMatchesEmailIgnoringCase() {
        userRepository.saveAndFlush(new User("alice@example.com", "Alice", "Doe"));

        assertThat(filtered("ALICE@EXAMPLE", null).getContent())
                .extracting(User::getEmail)
                .containsExactly("alice@example.com");
    }

    @Test
    void searchMatchesFirstNameAndLastNameIgnoringCase() {
        userRepository.saveAndFlush(new User("alice@example.com", "Alice", "Doe"));
        userRepository.saveAndFlush(new User("bob@example.com", "Bob", "Marley"));

        assertThat(filtered("doe", null).getContent()).extracting(User::getEmail)
                .containsExactly("alice@example.com");
        assertThat(filtered("MARL", null).getContent()).extracting(User::getEmail)
                .containsExactly("bob@example.com");
    }

    @Test
    void searchFiltersByStatus() {
        User inactive = new User("inactive@example.com", "Ina", "Ctive");
        inactive.setStatus(UserStatus.INACTIVE);
        userRepository.saveAndFlush(inactive);
        userRepository.saveAndFlush(new User("active@example.com", "Ac", "Tive"));

        assertThat(filtered(null, UserStatus.INACTIVE).getContent()).extracting(User::getEmail)
                .containsExactly("inactive@example.com");
        assertThat(filtered(null, UserStatus.ACTIVE).getContent()).extracting(User::getEmail)
                .containsExactly("active@example.com");
    }

    @Test
    void searchCombinesTermAndStatus() {
        User activeTarget = new User("target-active@example.com", "Target", "One");
        User inactiveTarget = new User("target-inactive@example.com", "Target", "Two");
        inactiveTarget.setStatus(UserStatus.INACTIVE);
        userRepository.saveAndFlush(activeTarget);
        userRepository.saveAndFlush(inactiveTarget);
        userRepository.saveAndFlush(new User("unrelated@example.com", "Other", "Name"));

        assertThat(filtered("target", UserStatus.INACTIVE).getContent()).extracting(User::getEmail)
                .containsExactly("target-inactive@example.com");
    }

    @Test
    void searchEscapesSqlWildcards() {
        userRepository.saveAndFlush(new User("literal%100@example.com", "Literal", "Percent"));
        userRepository.saveAndFlush(new User("other@example.com", "Other", "Name"));

        assertThat(filtered("literal%100", null).getContent()).extracting(User::getEmail)
                .containsExactly("literal%100@example.com");
        assertThat(filtered("%100", null).getContent()).extracting(User::getEmail)
                .containsExactly("literal%100@example.com");
    }

    @Test
    void searchRespectsPaginationAndSort() {
        userRepository.saveAndFlush(new User("a@example.com", "Aa", "Target"));
        userRepository.saveAndFlush(new User("b@example.com", "Bb", "Target"));
        userRepository.saveAndFlush(new User("c@example.com", "Cc", "Target"));

        Page<User> page = userRepository.findAll(
                UserSpecifications.withFilters("target", null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "email")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(User::getEmail).containsExactly("a@example.com", "b@example.com");
    }

    @Test
    void searchWithNoFiltersMatchesEveryUser() {
        userRepository.saveAndFlush(new User("one@example.com", "One", "User"));
        userRepository.saveAndFlush(new User("two@example.com", "Two", "User"));

        assertThat(filtered(null, null).getTotalElements()).isEqualTo(2);
        assertThat(filtered("   ", null).getTotalElements()).isEqualTo(2);
    }

    @Test
    void unknownSortPropertyIsRejectedBySpringData() {
        userRepository.saveAndFlush(new User("alice@example.com", "Alice", "Doe"));

        assertThatThrownBy(() -> userRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "notAProperty"))))
                .isInstanceOf(PropertyReferenceException.class);
    }

    private Page<User> filtered(String search, UserStatus status) {
        return userRepository.findAll(UserSpecifications.withFilters(search, status), PageRequest.of(0, 20));
    }
}
