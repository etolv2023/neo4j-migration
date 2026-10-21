-- Users can be switched off, and can sign in with a second factor (TOTP) and one-time recovery codes.
ALTER TABLE users
    ADD COLUMN enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN totp_secret_enc VARCHAR(255) NULL,
    ADD COLUMN totp_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN totp_last_step BIGINT       NOT NULL DEFAULT 0;

CREATE TABLE recovery_codes (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    code_hash  VARCHAR(100) NOT NULL,
    used_at    TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX ix_recovery_codes_user (user_id),
    CONSTRAINT fk_recovery_codes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
