package com.sdigital.platform.function.repository;

import com.sdigital.platform.function.entity.Function;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Function}.
 */
@Repository
public interface FunctionRepository extends JpaRepository<Function, UUID>, JpaSpecificationExecutor<Function> {

    Optional<Function> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Returns whether another function (id different from {@code id}) already uses the code.
     */
    boolean existsByCodeAndIdNot(String code, UUID id);
}
