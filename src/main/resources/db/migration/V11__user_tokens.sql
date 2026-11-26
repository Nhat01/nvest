-- V11: xác thực email và đặt lại mật khẩu.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

-- User đang dùng không bị khóa sau khi bật xác thực email.
UPDATE users SET email_verified_at = now() WHERE email_verified_at IS NULL;

CREATE TABLE user_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    purpose    VARCHAR(30) NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    CONSTRAINT ck_user_tokens_purpose CHECK (
        purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')
    )
);

CREATE UNIQUE INDEX ux_user_tokens_hash ON user_tokens (token_hash);
CREATE INDEX ix_user_tokens_user_purpose ON user_tokens (user_id, purpose);
CREATE INDEX ix_user_tokens_expires ON user_tokens (expires_at);

-- Thêm lý do thu hồi refresh token khi đặt lại mật khẩu.
ALTER TABLE refresh_tokens DROP CONSTRAINT ck_refresh_tokens_reason;
ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_reason CHECK (
    revoked_reason IS NULL
    OR revoked_reason IN (
        'ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED', 'USER_LOCKED',
        'PASSWORD_RESET'
    )
);