package com.sdigital.platform.function.repository;

import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.entity.FunctionStatus;
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
 * Integration tests for {@link FunctionRepository} running against a real PostgreSQL 16
 * container. Flyway runs the migrations and Hibernate validates the schema, so the
 * tests also cover the {@code V3__create_function.sql} migration and its constraints.
 *
 * <p>They also validate that the table name {@code function} is accepted by PostgreSQL 16
 * and by Hibernate's schema validation (it is not a reserved keyword in PostgreSQL).</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FunctionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private FunctionRepository functionRepository;

    @Test
    void savesFunctionWithGeneratedIdDefaultsAndTimestamps() {
        Function saved = functionRepository.saveAndFlush(new Function("F-INGE-01", "Engineer 01"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(FunctionStatus.ACTIVE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsFunctionByCode() {
        functionRepository.saveAndFlush(new Function("F-INGE-01", "Engineer 01"));

        Optional<Function> found = functionRepository.findByCode("F-INGE-01");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Engineer 01");
        assertThat(functionRepository.existsByCode("F-INGE-01")).isTrue();
    }

    @Test
    void rejectsDuplicateCode() {
        functionRepository.saveAndFlush(new Function("F-INGE-01", "Engineer 01"));

        assertThatThrownBy(() ->
                functionRepository.saveAndFlush(new Function("F-INGE-01", "Duplicate Function")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void codeRejectsOversizedValue() {
        String longCode = "A".repeat(51);

        assertThatThrownBy(() -> functionRepository.saveAndFlush(new Function(longCode, "Engineer")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nameRejectsOversizedValue() {
        String longName = "N".repeat(151);

        assertThatThrownBy(() -> functionRepository.saveAndFlush(new Function("F-LIMIT", longName)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Multicriteria filtering (FunctionSpecifications) -------------------------------------

    @Test
    void searchMatchesCodeIgnoringCase() {
        functionRepository.saveAndFlush(new Function("F-INGE-01", "Engineer 01"));

        assertThat(filtered("INGE", null).getContent())
                .extracting(Function::getCode)
                .containsExactly("F-INGE-01");
    }

    @Test
    void searchMatchesNameIgnoringCase() {
        functionRepository.saveAndFlush(new Function("F-INGE-01", "Engineer 01"));
        functionRepository.saveAndFlush(new Function("F-OPS-01", "Operations 01"));

        assertThat(filtered("operations", null).getContent()).extracting(Function::getCode)
                .containsExactly("F-OPS-01");
        assertThat(filtered("F-", null).getContent()).hasSize(2);
    }

    @Test
    void searchFiltersByStatus() {
        Function inactive = new Function("F-INGE-01", "Engineer 01");
        inactive.setStatus(FunctionStatus.INACTIVE);
        functionRepository.saveAndFlush(inactive);
        functionRepository.saveAndFlush(new Function("F-OPS-01", "Operations 01"));

        assertThat(filtered(null, FunctionStatus.INACTIVE).getContent()).extracting(Function::getCode)
                .containsExactly("F-INGE-01");
        assertThat(filtered(null, FunctionStatus.ACTIVE).getContent()).extracting(Function::getCode)
                .containsExactly("F-OPS-01");
    }

    @Test
    void searchCombinesTermAndStatus() {
        Function activeTarget = new Function("F-TARGET-01", "Target Active");
        Function inactiveTarget = new Function("F-TARGET-02", "Target Inactive");
        inactiveTarget.setStatus(FunctionStatus.INACTIVE);
        functionRepository.saveAndFlush(activeTarget);
        functionRepository.saveAndFlush(inactiveTarget);
        functionRepository.saveAndFlush(new Function("F-OTHER", "Other Function"));

        assertThat(filtered("target", FunctionStatus.INACTIVE).getContent()).extracting(Function::getCode)
                .containsExactly("F-TARGET-02");
    }

    @Test
    void searchEscapesSqlWildcards() {
        functionRepository.saveAndFlush(new Function("F-INGE-100%", "Literal Percent"));
        functionRepository.saveAndFlush(new Function("F-OTHER", "Other Function"));

        assertThat(filtered("100%", null).getContent()).extracting(Function::getCode)
                .containsExactly("F-INGE-100%");
    }

    @Test
    void searchWithNoFiltersMatchesEveryFunction() {
        functionRepository.saveAndFlush(new Function("F-01", "One"));
        functionRepository.saveAndFlush(new Function("F-02", "Two"));

        assertThat(filtered(null, null).getTotalElements()).isEqualTo(2);
        assertThat(filtered("   ", null).getTotalElements()).isEqualTo(2);
    }

    @Test
    void searchRespectsPaginationAndSort() {
        functionRepository.saveAndFlush(new Function("F-A", "Target A"));
        functionRepository.saveAndFlush(new Function("F-B", "Target B"));
        functionRepository.saveAndFlush(new Function("F-C", "Target C"));

        Page<Function> page = functionRepository.findAll(
                FunctionSpecifications.withFilters("target", null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "code")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Function::getCode).containsExactly("F-A", "F-B");
    }

    @Test
    void unknownSortPropertyIsRejectedBySpringData() {
        functionRepository.saveAndFlush(new Function("F-01", "One"));

        assertThatThrownBy(() -> functionRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "notAProperty"))))
                .isInstanceOf(PropertyReferenceException.class);
    }

    private Page<Function> filtered(String search, FunctionStatus status) {
        return functionRepository.findAll(FunctionSpecifications.withFilters(search, status), PageRequest.of(0, 20));
    }
}
