package com.sdigital.platform.audit.service;

import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AuditLogService} against a real PostgreSQL 16 container.
 *
 * <p>Only the audit persistence layer is loaded (no web, no security, no Keycloak), which
 * proves the service persists a valid entry end to end, that {@code occurredAt} and the id are
 * generated, and that {@code details} round-trips as JSONB.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditLogService.class)
@Testcontainers
class AuditLogServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void persistsEntryWithGeneratedIdAndTimestamp() {
        UUID actorId = UUID.randomUUID();

        AuditLog saved = auditLogService.record(actorId, "ROLE_REMOVE", "USER_ROLE", "target-2",
                AuditOutcome.SUCCESS, Map.of("role", "AGENT"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOccurredAt()).isNotNull();
        assertThat(auditLogRepository.findById(saved.getId())).isPresent();
        assertThat(auditLogRepository.count()).isEqualTo(1);
    }

    @Test
    void roundTripsDetailsAsJsonb() {
        AuditLog saved = auditLogService.record(null, "AGENCY_UPDATE", "AGENCY", "agency-1",
                AuditOutcome.SUCCESS, Map.of("field", "status", "to", "INACTIVE"));

        AuditLog reloaded = auditLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getDetails())
                .containsEntry("field", "status")
                .containsEntry("to", "INACTIVE");
    }

    @Test
    void persistsNullActorEntry() {
        AuditLog saved = auditLogService.record(null, "SYSTEM_TASK", "SYSTEM", null,
                AuditOutcome.NO_CHANGE, null);

        AuditLog reloaded = auditLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getActorUserId()).isNull();
        assertThat(reloaded.getOutcome()).isEqualTo(AuditOutcome.NO_CHANGE);
    }
}
