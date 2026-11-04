-- V6: Giao dich thanh toan qua ben thu ba (nap/rut). Xem R27-R31, R54-R56.
CREATE TABLE payment_transactions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    direction       VARCHAR(10)  NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    order_code      VARCHAR(64)  NOT NULL,                 -- ma don duy nhat cua minh (R28)
    provider        VARCHAR(30)  NOT NULL,
    provider_ref    VARCHAR(100),                          -- ma tham chieu phia cong thanh toan
    checkout_url    VARCHAR(500),
    idempotency_key VARCHAR(100) NOT NULL,                 -- khoa chong tao trung do client gui (R54)
    failure_reason  VARCHAR(255),
    raw_payload     TEXT,                                  -- noi dung webhook gan nhat, de doi soat
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_payment_order_code  UNIQUE (order_code),
    CONSTRAINT ux_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_direction   CHECK (direction IN ('DEPOSIT', 'WITHDRAW')),
    CONSTRAINT ck_payment_status      CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_payment_amount      CHECK (amount > 0)
);
CREATE UNIQUE INDEX ux_payment_provider_ref
    ON payment_transactions (provider, provider_ref) WHERE provider_ref IS NOT NULL;
CREATE INDEX ix_payment_user ON payment_transactions (user_id, id);
-- Job doi soat chi quet giao dich chua ro ket qua (PENDING/UNKNOWN), index mot phan cho nho va nhanh
CREATE INDEX ix_payment_open ON payment_transactions (created_at) WHERE status IN ('PENDING', 'UNKNOWN');
