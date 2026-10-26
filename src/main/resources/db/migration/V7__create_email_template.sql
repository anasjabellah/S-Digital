-- Email templates of the S-Digital platform (content models only; no delivery).
-- Created by the email-templates CRUD module. Does not send mail.

CREATE TABLE email_template
(
    id         UUID          NOT NULL,
    code       VARCHAR(100)  NOT NULL,
    name       VARCHAR(150)  NOT NULL,
    subject    VARCHAR(255)  NOT NULL,
    body       TEXT          NOT NULL,
    locale     VARCHAR(10)   NOT NULL,
    variables  JSONB         NOT NULL,
    status     VARCHAR(20)   NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_email_template PRIMARY KEY (id),
    CONSTRAINT uk_email_template_code UNIQUE (code)
);

CREATE INDEX ix_email_template_status ON email_template (status);
CREATE INDEX ix_email_template_locale ON email_template (locale);
