-- Agencies of the S-Digital platform (reference data).
-- Created by lot 1 of the agencies & functions module (etape 4C.2).

CREATE TABLE agency
(
    id         UUID         NOT NULL,
    code       VARCHAR(50)  NOT NULL,
    name       VARCHAR(150) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_agency PRIMARY KEY (id),
    CONSTRAINT uk_agency_code UNIQUE (code)
);

CREATE INDEX ix_agency_status ON agency (status);
