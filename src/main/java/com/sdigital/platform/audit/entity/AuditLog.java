package com.sdigital.platform.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A single persistent audit event.
 *
 * <p>The schema is owned by Flyway (see {@code V5__create_audit_log.sql}); Hibernate is
 * configured with {@code ddl-auto=validate} and never alters the schema.</p>
 *
 * <p>This is an append-only record: the entity exposes no setter and application code
 * only creates entries. It is an ordinary database row, not a tamper-proof journal.
 * {@code actorUserId} and {@code entityId} are loose references without a foreign key,
 * so an audit entry survives the deletion of the user or resource it refers to.</p>
 *
 * <p>The {@code details} column is JSONB, mapped through Hibernate's native JSON support
 * ({@link JdbcTypeCode} + {@link SqlTypes#JSON}) so no additional dependency is needed.
 * Callers must only pass structured, explicitly selected metadata: no tokens, secrets,
 * authorization headers, request bodies or full personal data.</p>
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /**
     * Local id of the acting user, or {@code null} when the actor is not resolved
     * (system or unauthenticated context).
     */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 64, updatable = false)
    private String entityType;

    @Column(name = "entity_id", length = 64, updatable = false)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private AuditOutcome outcome;

    /**
     * Structured, strictly filtered metadata. Never contains sensitive values.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", updatable = false)
    private Map<String, Object> details;

    /**
     * Required by JPA. Must not be used directly by application code.
     */
    protected AuditLog() {
    }

    public AuditLog(UUID actorUserId,
                    String action,
                    String entityType,
                    String entityId,
                    AuditOutcome outcome,
                    Map<String, Object> details) {
        this.actorUserId = actorUserId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.details = details;
    }

    @PrePersist
    void onCreate() {
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
