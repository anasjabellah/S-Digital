package com.sdigital.platform.audit.service;

import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditLogService}. The repository is mocked: only the validation
 * and construction logic is exercised here. The actual persistence (including JSONB) is
 * covered by {@link AuditLogServiceIntegrationTest} against a real PostgreSQL container.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository);
    }

    @Test
    void recordsEntryWithAllFields() {
        UUID actorId = UUID.randomUUID();
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog result = auditLogService.record(actorId, "ROLE_ASSIGN", "USER_ROLE", "target-1",
                AuditOutcome.SUCCESS, Map.of("role", "MANAGER"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog persisted = captor.getValue();
        assertThat(persisted.getActorUserId()).isEqualTo(actorId);
        assertThat(persisted.getAction()).isEqualTo("ROLE_ASSIGN");
        assertThat(persisted.getEntityType()).isEqualTo("USER_ROLE");
        assertThat(persisted.getEntityId()).isEqualTo("target-1");
        assertThat(persisted.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(persisted.getDetails()).containsEntry("role", "MANAGER");
        assertThat(result).isSameAs(persisted);
    }

    @Test
    void acceptsNullActorAndNullDetails() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog result = auditLogService.record(null, "SYSTEM_TASK", "SYSTEM", null,
                AuditOutcome.SUCCESS, null);

        assertThat(result.getActorUserId()).isNull();
        assertThat(result.getDetails()).isNull();
        assertThat(result.getEntityId()).isNull();
    }

    @Test
    void trimsActionAndEntityType() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog result = auditLogService.record(null, "  USER_CREATE  ", "  USER  ", null,
                AuditOutcome.SUCCESS, null);

        assertThat(result.getAction()).isEqualTo("USER_CREATE");
        assertThat(result.getEntityType()).isEqualTo("USER");
    }

    @Test
    void rejectsBlankAction() {
        assertThatThrownBy(() -> auditLogService.record(null, "  ", "USER", null,
                AuditOutcome.SUCCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void rejectsNullAction() {
        assertThatThrownBy(() -> auditLogService.record(null, null, "USER", null,
                AuditOutcome.SUCCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void rejectsBlankEntityType() {
        assertThatThrownBy(() -> auditLogService.record(null, "USER_CREATE", "  ", null,
                AuditOutcome.SUCCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityType");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void rejectsBlankEntityIdWhenProvided() {
        assertThatThrownBy(() -> auditLogService.record(null, "USER_CREATE", "USER", "   ",
                AuditOutcome.SUCCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void rejectsNullOutcome() {
        assertThatThrownBy(() -> auditLogService.record(null, "USER_CREATE", "USER", null,
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void rejectsOversizedAction() {
        String tooLong = "A".repeat(65);

        assertThatThrownBy(() -> auditLogService.record(null, tooLong, "USER", null,
                AuditOutcome.SUCCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void rejectsOversizedEntityType() {
        String tooLong = "T".repeat(65);

        assertThatThrownBy(() -> auditLogService.record(null, "USER_CREATE", tooLong, null,
                AuditOutcome.SUCCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityType");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void rejectsOversizedEntityId() {
        String tooLong = "I".repeat(65);

        assertThatThrownBy(() -> auditLogService.record(null, "USER_CREATE", "USER", tooLong,
                AuditOutcome.SUCCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }
}
