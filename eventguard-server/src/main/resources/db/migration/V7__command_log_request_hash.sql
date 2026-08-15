ALTER TABLE command_log
    ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_command_log_request_hash
    ON command_log (request_hash);
