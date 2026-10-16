package com.sdigital.platform.user.repository;

import com.sdigital.platform.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link User}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Returns whether another user (id different from {@code id}) already uses the email.
     * Used during updates to avoid a false conflict with the user being modified.
     */
    boolean existsByEmailAndIdNot(String email, UUID id);

    Optional<User> findByKeycloakId(String keycloakId);

    boolean existsByKeycloakId(String keycloakId);
}
