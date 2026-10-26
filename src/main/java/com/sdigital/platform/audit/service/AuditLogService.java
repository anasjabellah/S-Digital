package com.sdigital.platform.audit.service;

import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

/**
 * Persistence service for audit events.
 *
 * <p>This is the single entry point used by the future audit integrations (role changes,
 * user / agency / function operations). It only persists a record: it never calls another
 * business service, never touches security or Keycloak, and never changes the behaviour of
 * existing endpoints.</p>
 *
 * <p>By default the write joins the caller's transaction ({@code REQUIRED} propagation): the
 * audit entry is then committed with the business mutation and rolled back with it, which
 * is what an audit of a local mutation must guarantee. Called outside any transaction, the
 * write runs on its own.</p>
 *
 * <p>{@link #recordIndependent(UUID, String, String, String, AuditOutcome, Map)} is the
 * counterpart for events that must outlive a rollback, such as a refused mutation: it always
 * commits on its own.</p>
 *
 * <p>The service does not (and cannot) make external Keycloak operations atomic with the
 * PostgreSQL transaction: a database transaction only covers the database.</p>
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Persists a single audit event.
     *
     * <p>The write joins the caller's transaction when there is one: the entry is committed
     * with the business mutation and rolled back with it.</p>
     *
     * @param actorUserId local id of the acting user, or {@code null} when unresolved
     * @param action      stable action code (required, at most 64 characters)
     * @param entityType  affected resource type (required, at most 64 characters)
     * @param entityId    affected resource id, or {@code null} (at most 64 characters)
     * @param outcome     operation outcome (required)
     * @param details     structured, strictly filtered metadata, or {@code null}
     * @return the persisted {@link AuditLog} (with generated id and occurredAt)
     * @throws IllegalArgumentException if a required field is missing, blank, oversized or null
     */
    @Transactional
    public AuditLog record(UUID actorUserId,
                           String action,
                           String entityType,
                           String entityId,
                           AuditOutcome outcome,
                           Map<String, Object> details) {
        return persist(actorUserId, action, entityType, entityId, outcome, details);
    }

    /**
     * Persists a single audit event in an independent transaction.
     *
     * <p>This is required for events that must survive the rollback of the operation they
     * describe, typically a <em>refused</em> mutation: the refusal ends with an exception, and
     * that exception rolls back the caller's transaction. A denial recorded in the caller's
     * transaction would therefore be discarded exactly when it matters most.</p>
     *
     * <p>The independent transaction is committed on its own: the caller's transaction is
     * suspended for the duration of the write and is neither extended nor committed by it.</p>
     *
     * @param actorUserId local id of the acting user, or {@code null} when unresolved
     * @param action      stable action code (required, at most 64 characters)
     * @param entityType  affected resource type (required, at most 64 characters)
     * @param entityId    affected resource id, or {@code null} (at most 64 characters)
     * @param outcome     operation outcome (required)
     * @param details     structured, strictly filtered metadata, or {@code null}
     * @return the persisted {@link AuditLog} (with generated id and occurredAt)
     * @throws IllegalArgumentException if a required field is missing, blank, oversized or null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog recordIndependent(UUID actorUserId,
                                      String action,
                                      String entityType,
                                      String entityId,
                                      AuditOutcome outcome,
                                      Map<String, Object> details) {
        return persist(actorUserId, action, entityType, entityId, outcome, details);
    }

    private AuditLog persist(UUID actorUserId,
                             String action,
                             String entityType,
                             String entityId,
                             AuditOutcome outcome,
                             Map<String, Object> details) {
        String validatedAction = requireText(action, "action", 64);
        String validatedEntityType = requireText(entityType, "entityType", 64);
        String validatedEntityId = optionalText(entityId, "entityId", 64);
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }

        AuditLog entry = new AuditLog(
                actorUserId,
                validatedAction,
                validatedEntityType,
                validatedEntityId,
                outcome,
                details);
        return auditLogRepository.save(entry);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank when provided");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }
}
