-- Persistent in-app notifications for platform users.
-- Created by the notification persistence layer (Notifications module).
--
-- This table stores notifications addressed to a single local user. A user can only
-- read or mark-as-read their own notifications; cross-user access is prevented by the
-- service layer (ownership check on user_id).
--
-- user_id intentionally has NO foreign key: a notification must survive the deletion or
-- deactivation of the referenced user, mirroring the audit_log convention.

CREATE TABLE notification
(
    id        UUID        NOT NULL,
    user_id   UUID        NOT NULL,
    title     VARCHAR(200) NOT NULL,
    message   VARCHAR(1000) NOT NULL,
    type      VARCHAR(50)  NOT NULL,
    read      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    read_at   TIMESTAMPTZ,
    link      VARCHAR(500),
    metadata  JSONB,
    CONSTRAINT pk_notification PRIMARY KEY (id)
);

-- All notifications of a user, newest first.
CREATE INDEX ix_notification_user_created ON notification (user_id, created_at DESC);

-- Unread notifications of a user (used by the unread listing and count).
CREATE INDEX ix_notification_user_read ON notification (user_id, read, created_at DESC);
