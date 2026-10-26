package com.sdigital.platform.email.repository;

import com.sdigital.platform.email.entity.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link EmailTemplate}.
 */
@Repository
public interface EmailTemplateRepository
        extends JpaRepository<EmailTemplate, UUID>, JpaSpecificationExecutor<EmailTemplate> {

    Optional<EmailTemplate> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Returns whether another template (id different from {@code id}) already uses the code.
     */
    boolean existsByCodeAndIdNot(String code, UUID id);
}
