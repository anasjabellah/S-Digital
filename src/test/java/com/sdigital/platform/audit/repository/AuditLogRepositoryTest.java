package com.sdigital.platform.audit.repository;

import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link AuditLogRepository} running against a real PostgreSQL 16
 * container. Flyway runs the migrations (including {@code V5__create_audit_log.sql}) and
 * Hibernate validates the schema, so this also covers the migration and the JSONB mapping.
 *
 * <p>H2 is intentionally not used: only a real PostgreSQL instance proves the {@code JSONB}
 * column type and its {@code CHECK} constraints behave as expected.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuditLogRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAuditEntryWithGeneratedIdAndOccurredAt() {
        AuditLog saved = auditLogRepository.saveAndFlush(new AuditLog(
                UUID.randomUUID(), "USER_CREATE", "USER", UUID.randomUUID().toString(),
                AuditOutcome.SUCCESS, null));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void persistsAndReloadsMainFields() {
        UUID actorId = UUID.randomUUID();
        String entityId = UUID.randomUUID().toString();

        AuditLog saved = auditLogRepository.saveAndFlush(new AuditLog(
                actorId, "ROLE_ASSIGN", "USER_ROLE", entityId, AuditOutcome.SUCCESS, null));
        UUID id = saved.getId();
        entityManager.clear();

        AuditLog reloaded = auditLogRepository.findById(id).orElseThrow();

        assertThat(reloaded.getActorUserId()).isEqualTo(actorId);
        assertThat(reloaded.getAction()).isEqualTo("ROLE_ASSIGN");
        assertThat(reloaded.getEntityType()).isEqualTo("USER_ROLE");
        assertThat(reloaded.getEntityId()).isEqualTo(entityId);
        assertThat(reloaded.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(reloaded.getOccurredAt()).isNotNull();
    }

    @Test
    void persistsJsonbDetailsAndReloadsThem() {
        AuditLog saved = auditLogRepository.saveAndFlush(new AuditLog(
                null, "ROLE_ASSIGN", "USER_ROLE", "target-1", AuditOutcome.SUCCESS,
                Map.of("role", "MANAGER", "changed", true, "count", 1)));
        UUID id = saved.getId();
        entityManager.clear();

        AuditLog reloaded = auditLogRepository.findById(id).orElseThrow();

        assertThat(reloaded.getDetails())
                .containsEntry("role", "MANAGER")
                .containsEntry("changed", true)
                .containsEntry("count", 1);
    }

    @Test
    void storesNullDetailsWithoutError() {
        AuditLog saved = auditLogRepository.saveAndFlush(new AuditLog(
                null, "USER_DEACTIVATE", "USER", null, AuditOutcome.SUCCESS, null));
        entityManager.clear();

        AuditLog reloaded = auditLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getDetails()).isNull();
        assertThat(reloaded.getEntityId()).isNull();
    }

    @Test
    void allowsNullActorUserId() {
        AuditLog saved = auditLogRepository.saveAndFlush(new AuditLog(
                null, "SYSTEM_TASK", "SYSTEM", null, AuditOutcome.SUCCESS, null));

        assertThat(saved.getActorUserId()).isNull();
        assertThat(auditLogRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsNullOccurredAtAtDatabaseLevel() {
        // The entity always sets occurredAt via @PrePersist, so a bypassing native INSERT is
        // needed to prove the column's NOT NULL constraint is enforced by PostgreSQL itself.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "INSERT INTO audit_log (id, occurred_at, action, entity_type, outcome) "
                                    + "VALUES (:id, NULL, 'USER_CREATE', 'USER', 'SUCCESS')")
                    .setParameter("id", UUID.randomUUID())
                    .executeUpdate();
        }).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void storesEveryAllowedOutcome() {
        for (AuditOutcome outcome : List.of(AuditOutcome.SUCCESS, AuditOutcome.DENIED,
                AuditOutcome.FAILURE, AuditOutcome.NO_CHANGE)) {
            auditLogRepository.saveAndFlush(new AuditLog(
                    null, "OP", "USER", null, outcome, null));
        }

        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getOutcome)
                .containsExactlyInAnyOrder(AuditOutcome.SUCCESS, AuditOutcome.DENIED,
                        AuditOutcome.FAILURE, AuditOutcome.NO_CHANGE);
    }

    @Test
    void rejectsActionExceedingColumnLength() {
        String tooLong = "A".repeat(65);

        assertThatThrownBy(() -> auditLogRepository.saveAndFlush(new AuditLog(
                null, tooLong, "USER", null, AuditOutcome.SUCCESS, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
