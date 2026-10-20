-- Add agency and function assignment to users (one agency, one function per user).
-- Columns are nullable to preserve existing users without data backfill.

ALTER TABLE app_user
    ADD COLUMN agency_id UUID,
    ADD COLUMN function_id UUID;

ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_agency
        FOREIGN KEY (agency_id) REFERENCES agency (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_app_user_function
        FOREIGN KEY (function_id) REFERENCES function (id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_app_user_agency_id ON app_user (agency_id);
CREATE INDEX IF NOT EXISTS idx_app_user_function_id ON app_user (function_id);
