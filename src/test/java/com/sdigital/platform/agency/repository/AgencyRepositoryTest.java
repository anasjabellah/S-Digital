package com.sdigital.platform.agency.repository;

import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
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
 * Integration tests for {@link AgencyRepository} running against a real PostgreSQL 16
 * container. Flyway runs the migrations and Hibernate validates the schema, so the
 * tests also cover the {@code V2__create_agency.sql} migration and its constraints.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AgencyRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private AgencyRepository agencyRepository;

    @Test
    void savesAgencyWithGeneratedIdDefaultsAndTimestamps() {
        Agency saved = agencyRepository.saveAndFlush(new Agency("AG-PARIS-01", "Agency Paris 01"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(AgencyStatus.ACTIVE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsAgencyByCode() {
        agencyRepository.saveAndFlush(new Agency("AG-PARIS-01", "Agency Paris 01"));

        Optional<Agency> found = agencyRepository.findByCode("AG-PARIS-01");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Agency Paris 01");
        assertThat(agencyRepository.existsByCode("AG-PARIS-01")).isTrue();
    }

    @Test
    void rejectsDuplicateCode() {
        agencyRepository.saveAndFlush(new Agency("AG-PARIS-01", "Agency Paris 01"));

        assertThatThrownBy(() ->
                agencyRepository.saveAndFlush(new Agency("AG-PARIS-01", "Agency Duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void codeRejectsOversizedValue() {
        String longCode = "A".repeat(51);

        assertThatThrownBy(() -> agencyRepository.saveAndFlush(new Agency(longCode, "Agency")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nameRejectsOversizedValue() {
        String longName = "N".repeat(151);

        assertThatThrownBy(() -> agencyRepository.saveAndFlush(new Agency("AG-LIMIT", longName)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Multicriteria filtering (AgencySpecifications) ---------------------------------------

    @Test
    void searchMatchesCodeIgnoringCase() {
        agencyRepository.saveAndFlush(new Agency("AG-PARIS-01", "Agency Paris 01"));

        assertThat(filtered("PARIS", null).getContent())
                .extracting(Agency::getCode)
                .containsExactly("AG-PARIS-01");
    }

    @Test
    void searchMatchesNameIgnoringCase() {
        agencyRepository.saveAndFlush(new Agency("AG-PARIS-01", "Agency Paris 01"));
        agencyRepository.saveAndFlush(new Agency("AG-LYON-01", "Agency Lyon 01"));

        assertThat(filtered("lyon", null).getContent()).extracting(Agency::getCode)
                .containsExactly("AG-LYON-01");
        assertThat(filtered("AG-", null).getContent()).hasSize(2);
    }

    @Test
    void searchFiltersByStatus() {
        Agency inactive = new Agency("AG-PARIS-01", "Agency Paris 01");
        inactive.setStatus(AgencyStatus.INACTIVE);
        agencyRepository.saveAndFlush(inactive);
        agencyRepository.saveAndFlush(new Agency("AG-LYON-01", "Agency Lyon 01"));

        assertThat(filtered(null, AgencyStatus.INACTIVE).getContent()).extracting(Agency::getCode)
                .containsExactly("AG-PARIS-01");
        assertThat(filtered(null, AgencyStatus.ACTIVE).getContent()).extracting(Agency::getCode)
                .containsExactly("AG-LYON-01");
    }

    @Test
    void searchCombinesTermAndStatus() {
        Agency activeTarget = new Agency("AG-TARGET-01", "Target Active");
        Agency inactiveTarget = new Agency("AG-TARGET-02", "Target Inactive");
        inactiveTarget.setStatus(AgencyStatus.INACTIVE);
        agencyRepository.saveAndFlush(activeTarget);
        agencyRepository.saveAndFlush(inactiveTarget);
        agencyRepository.saveAndFlush(new Agency("AG-OTHER", "Other Agency"));

        assertThat(filtered("target", AgencyStatus.INACTIVE).getContent()).extracting(Agency::getCode)
                .containsExactly("AG-TARGET-02");
    }

    @Test
    void searchEscapesSqlWildcards() {
        agencyRepository.saveAndFlush(new Agency("AG-PARIS-100%", "Literal Percent"));
        agencyRepository.saveAndFlush(new Agency("AG-OTHER", "Other Agency"));

        assertThat(filtered("100%", null).getContent()).extracting(Agency::getCode)
                .containsExactly("AG-PARIS-100%");
    }

    @Test
    void searchWithNoFiltersMatchesEveryAgency() {
        agencyRepository.saveAndFlush(new Agency("AG-01", "One"));
        agencyRepository.saveAndFlush(new Agency("AG-02", "Two"));

        assertThat(filtered(null, null).getTotalElements()).isEqualTo(2);
        assertThat(filtered("   ", null).getTotalElements()).isEqualTo(2);
    }

    @Test
    void searchRespectsPaginationAndSort() {
        agencyRepository.saveAndFlush(new Agency("AG-A", "Target A"));
        agencyRepository.saveAndFlush(new Agency("AG-B", "Target B"));
        agencyRepository.saveAndFlush(new Agency("AG-C", "Target C"));

        Page<Agency> page = agencyRepository.findAll(
                AgencySpecifications.withFilters("target", null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "code")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Agency::getCode).containsExactly("AG-A", "AG-B");
    }

    @Test
    void unknownSortPropertyIsRejectedBySpringData() {
        agencyRepository.saveAndFlush(new Agency("AG-01", "One"));

        assertThatThrownBy(() -> agencyRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "notAProperty"))))
                .isInstanceOf(PropertyReferenceException.class);
    }

    private Page<Agency> filtered(String search, AgencyStatus status) {
        return agencyRepository.findAll(AgencySpecifications.withFilters(search, status), PageRequest.of(0, 20));
    }
}
