package com.sdigital.platform.agency.repository;

import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Agency}.
 */
@Repository
public interface AgencyRepository extends JpaRepository<Agency, UUID>, JpaSpecificationExecutor<Agency> {

    Optional<Agency> findByCode(String code);

    /**
     * Counts agencies having the given lifecycle status. Backed by a derived query,
     * so the count is computed by the database without loading any entity.
     */
    long countByStatus(AgencyStatus status);

    boolean existsByCode(String code);

    /**
     * Returns whether another agency (id different from {@code id}) already uses the code.
     */
    boolean existsByCodeAndIdNot(String code, UUID id);
}
