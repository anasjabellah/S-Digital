package com.sdigital.platform.audit.mapper;

import com.sdigital.platform.audit.dto.AuditLogResponse;
import com.sdigital.platform.audit.entity.AuditLog;
import org.springframework.stereotype.Component;

/**
 * Manual mapper between the {@link AuditLog} entity and its read model.
 *
 * <p>Pure translation only: no persistence access and no business logic. The entity is
 * never returned to the client, so only the fields explicitly part of
 * {@link AuditLogResponse} are copied.</p>
 */
@Component
public class AuditLogMapper {

    /**
     * Maps an entity to its read model.
     */
    public AuditLogResponse toResponse(AuditLog entry) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getOccurredAt(),
                entry.getActorUserId(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getOutcome(),
                entry.getDetails()
        );
    }
}
