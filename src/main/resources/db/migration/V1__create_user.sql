-- Users of the S-Digital platform.
-- Table is named "app_user" because "user" is a reserved keyword in PostgreSQL.

CREATE TABLE app_user
(
    id          UUID         NOT NULL,
    email       VARCHAR(320) NOT NULL,
    keycloak_id VARCHAR(255),
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT uk_app_user_keycloak_id UNIQUE (keycloak_id)
);
