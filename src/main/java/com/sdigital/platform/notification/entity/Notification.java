package com.sdigital.platform.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A single in-app notification addressed to one local user.
 *
 * <p>The schema is owned by Flyway (see {@code V6__create_notification.sql}); Hibernate is
 * configured with {@code ddl-auto=validate} and never alters the schema.</p>
 *
 * <p>{@code user_id} is a loose reference without a foreign key, so a notification survives
 * the deletion of the referenced user, mirroring {@code audit_log}.</p>
 *
 * <p>The {@code metadata} column is JSONB and holds strictly selected, non-sensitive context
 * (for example a related resource id). No tokens, secrets or credentials are ever stored.</p>
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "message", nullable = false, length = 1000, updatable = false)
    private String message;

    @Column(name = "type", nullable = false, length = 50, updatable = false)
    private String type;

    @Column(name = "read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "link", length = 500)
    private String link;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    /**
     * Required by JPA. Must not be used directly by application code.
     */
    protected Notification() {
    }

    public Notification(UUID userId, String title, String message, String type, String link,
                        Map<String, Object> metadata) {
        this.userId = userId;
        this.title = title;
        this.message = message;
        this.type = type;
        this.link = link;
        this.metadata = metadata;
        this.read = false;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        // intentionally empty: updatedAt is not stored; read_at is set explicitly.
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getType() {
        return type;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public String getLink() {
        return link;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void markRead() {
        this.read = true;
        this.readAt = Instant.now();
    }
}
