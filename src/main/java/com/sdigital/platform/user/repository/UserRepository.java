package com.sdigital.platform.user.repository;

import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link User}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    /**
     * Counts users having the given lifecycle status. Backed by a derived query,
     * so the count is computed by the database without loading any entity.
     */
    long countByStatus(UserStatus status);

    boolean existsByEmail(String email);

    /**
     * Returns whether another user (id different from {@code id}) already uses the email.
     * Used during updates to avoid a false conflict with the user being modified.
     */
    boolean existsByEmailAndIdNot(String email, UUID id);

    Optional<User> findByKeycloakId(String keycloakId);

    boolean existsByKeycloakId(String keycloakId);
}
