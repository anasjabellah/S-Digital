-- Persistent audit log for the S-Digital platform.
-- Created by the audit persistence layer (etape 4H.2).
--
-- This table stores a durable, consultable audit trail of sensitive business
-- operations. It is an ordinary PostgreSQL table: it is NOT an immutable or
-- tamper-proof journal. No row is ever updated or deleted by the application.
--
-- actor_user_id intentionally has NO foreign key: an audit entry must survive the
-- deletion or deactivation of the referenced user. entity_id is a loose reference
-- (no FK) for the same reason.

CREATE TABLE audit_log
(
    id            UUID        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    actor_user_id UUID,
    action        VARCHAR(64) NOT NULL,
    entity_type   VARCHAR(64) NOT NULL,
    entity_id     VARCHAR(64),
    outcome       VARCHAR(16) NOT NULL,
    details       JSONB,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_outcome
        CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE', 'NO_CHANGE'))
);

-- Chronological listing support (descending reads).
CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at);

-- Filtering by acting local user.
CREATE INDEX ix_audit_log_actor_user_id ON audit_log (actor_user_id);

-- Filtering by target resource.
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id);
