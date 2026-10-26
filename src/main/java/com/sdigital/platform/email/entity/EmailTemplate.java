package com.sdigital.platform.email.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A reusable email content model (subject, body and declared placeholders).
 *
 * <p>The schema is owned by Flyway (see {@code V7__create_email_template.sql}); Hibernate is
 * configured with {@code ddl-auto=validate} and never alters the schema.</p>
 *
 * <p>This entity stores the template only. It is not an outbox and never triggers SMTP
 * delivery.</p>
 */
@Entity
@Table(name = "email_template")
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false)
    private List<String> variables = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailTemplateStatus status = EmailTemplateStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Required by JPA. Must not be used directly by application code.
     */
    protected EmailTemplate() {
    }

    public EmailTemplate(String code, String name, String subject, String body, String locale,
                         List<String> variables) {
        this.code = code;
        this.name = name;
        this.subject = subject;
        this.body = body;
        this.locale = locale;
        this.variables = variables == null ? new ArrayList<>() : new ArrayList<>(variables);
        this.status = EmailTemplateStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.variables == null) {
            this.variables = new ArrayList<>();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public List<String> getVariables() {
        return variables;
    }

    public void setVariables(List<String> variables) {
        this.variables = variables == null ? new ArrayList<>() : new ArrayList<>(variables);
    }

    public EmailTemplateStatus getStatus() {
        return status;
    }

    public void setStatus(EmailTemplateStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
