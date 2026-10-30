-- =====================================================================
-- V2: Schema cốt lõi của Nvest (xem docs/business-rules.md)
-- Quy ước: tiền = BIGINT (VND), lãi suất = NUMERIC(7,4) dạng thập phân (0.0600 = 6%),
--          ngày nghiệp vụ = DATE, thời điểm = TIMESTAMPTZ.
-- =====================================================================

-- ---------- USERS ----------
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_role   CHECK (role   IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED'))
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

-- ---------- WALLETS ----------
-- Ví của user và 3 ví hệ thống. available/held là số dư tổng hợp,
-- chỉ được cập nhật CÙNG transaction với việc ghi ledger (R33).
CREATE TABLE wallets (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wallet_type       VARCHAR(30) NOT NULL,
    user_id           BIGINT REFERENCES users (id),
    available_balance BIGINT      NOT NULL DEFAULT 0,
    held_balance      BIGINT      NOT NULL DEFAULT 0,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wallets_type CHECK (wallet_type IN
        ('USER', 'PLATFORM_EXTERNAL', 'PLATFORM_SAVINGS_CUSTODY', 'PLATFORM_INTEREST_POOL')),
    CONSTRAINT ck_wallets_owner CHECK (
        (wallet_type = 'USER' AND user_id IS NOT NULL) OR
        (wallet_type <> 'USER' AND user_id IS NULL)),
    -- Chốt chặn cuối cùng: ví user không bao giờ âm, kể cả khi code có bug
    CONSTRAINT ck_wallets_user_no_overdraft CHECK (
        wallet_type <> 'USER' OR (available_balance >= 0 AND held_balance >= 0))
);
CREATE UNIQUE INDEX ux_wallets_user        ON wallets (user_id)     WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_wallets_system_type ON wallets (wallet_type) WHERE wallet_type <> 'USER';

-- ---------- LEDGER (sổ cái, chỉ ghi thêm) ----------
CREATE TABLE ledger_transactions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tx_type         VARCHAR(30)  NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    reference_type  VARCHAR(30),
    reference_id    BIGINT,
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_ledger_tx_idempotency UNIQUE (idempotency_key)      -- R35
);

CREATE TABLE ledger_entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES ledger_transactions (id),
    wallet_id      BIGINT      NOT NULL REFERENCES wallets (id),
    balance_type   VARCHAR(10) NOT NULL,
    amount         BIGINT      NOT NULL,   -- dương = cộng, âm = trừ
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entries_balance_type CHECK (balance_type IN ('AVAILABLE', 'HELD')),
    CONSTRAINT ck_ledger_entries_amount       CHECK (amount <> 0)
);
CREATE INDEX ix_ledger_entries_wallet ON ledger_entries (wallet_id, id);
CREATE INDEX ix_ledger_entries_tx     ON ledger_entries (transaction_id);

-- R32: sổ cái bất biến, cấm UPDATE/DELETE ngay ở tầng database
CREATE FUNCTION forbid_ledger_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger is append-only (R32): % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

CREATE TRIGGER trg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------- SAVINGS PRODUCTS ----------
CREATE TABLE savings_products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(30)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    term_days   INT           NOT NULL,
    annual_rate NUMERIC(7, 4) NOT NULL,
    min_amount  BIGINT        NOT NULL,
    max_amount  BIGINT        NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'ON_SALE',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_savings_products_code UNIQUE (code),
    CONSTRAINT ck_products_term   CHECK (term_days > 0),
    CONSTRAINT ck_products_rate   CHECK (annual_rate >= 0 AND annual_rate <= 0.2000),
    CONSTRAINT ck_products_amount CHECK (min_amount > 0 AND max_amount >= min_amount),
    CONSTRAINT ck_products_status CHECK (status IN ('ON_SALE', 'STOPPED'))
);

-- ---------- SAVINGS ACCOUNTS (gói của user) ----------
CREATE TABLE savings_accounts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT        NOT NULL REFERENCES users (id),
    product_id      BIGINT        NOT NULL REFERENCES savings_products (id),
    parent_id       BIGINT        REFERENCES savings_accounts (id),   -- gói kỳ trước (tái tục)
    cycle_no        INT           NOT NULL DEFAULT 1,
    principal       BIGINT        NOT NULL,
    annual_rate     NUMERIC(7, 4) NOT NULL,   -- R5: chốt lúc mở gói
    term_days       INT           NOT NULL,   -- chốt lúc mở gói
    start_date      DATE          NOT NULL,
    maturity_date   DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    maturity_option VARCHAR(20)   NOT NULL,
    closed_at       TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,   -- optimistic locking
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_principal CHECK (principal > 0),
    CONSTRAINT ck_accounts_rate      CHECK (annual_rate >= 0),
    CONSTRAINT ck_accounts_term      CHECK (term_days > 0),
    CONSTRAINT ck_accounts_cycle     CHECK (cycle_no >= 1),
    CONSTRAINT ck_accounts_dates     CHECK (maturity_date = start_date + term_days),   -- R9
    CONSTRAINT ck_accounts_status    CHECK (status IN
        ('ACTIVE', 'MATURED', 'CLOSED', 'RENEWED', 'CLOSED_EARLY')),
    CONSTRAINT ck_accounts_option    CHECK (maturity_option IN
        ('PAYOUT_ALL', 'RENEW_PRINCIPAL', 'RENEW_ALL'))
);
CREATE INDEX ix_savings_accounts_user ON savings_accounts (user_id);
-- Job đáo hạn chỉ quét gói ACTIVE, nên index một phần cho nhỏ và nhanh
CREATE INDEX ix_savings_accounts_due ON savings_accounts (maturity_date) WHERE status = 'ACTIVE';
-- Một gói chỉ được tái tục đúng một lần (chống tạo trùng khi job chạy lại)
CREATE UNIQUE INDEX ux_savings_accounts_parent ON savings_accounts (parent_id) WHERE parent_id IS NOT NULL;

-- ---------- INTEREST ACCRUALS (lãi tạm tính từng ngày) ----------
CREATE TABLE interest_accruals (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   BIGINT      NOT NULL REFERENCES savings_accounts (id),
    day_number   INT         NOT NULL,
    accrual_date DATE        NOT NULL,
    amount       BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_accruals_account_date UNIQUE (account_id, accrual_date),   -- job chạy lại không trùng
    CONSTRAINT ux_accruals_account_day  UNIQUE (account_id, day_number),
    CONSTRAINT ck_accruals_day    CHECK (day_number >= 1),
    CONSTRAINT ck_accruals_amount CHECK (amount >= 0)
);

-- ---------- SETTLEMENTS (kết quả đáo hạn / rút sớm) ----------
CREATE TABLE settlements (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id            BIGINT      NOT NULL REFERENCES savings_accounts (id),
    settlement_type       VARCHAR(20) NOT NULL,
    option_applied        VARCHAR(20) NOT NULL,
    principal             BIGINT      NOT NULL,
    interest              BIGINT      NOT NULL,
    new_account_id        BIGINT      REFERENCES savings_accounts (id),
    ledger_transaction_id BIGINT      NOT NULL REFERENCES ledger_transactions (id),
    fallback_reason       VARCHAR(100),   -- R18: lý do tự chuyển sang PAYOUT_ALL
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Mỗi gói (mỗi kỳ là một dòng savings_accounts riêng) chỉ được quyết toán một lần
    CONSTRAINT ux_settlements_account UNIQUE (account_id),
    CONSTRAINT ck_settlements_type   CHECK (settlement_type IN ('MATURITY', 'EARLY_WITHDRAWAL')),
    CONSTRAINT ck_settlements_option CHECK (option_applied IN
        ('PAYOUT_ALL', 'RENEW_PRINCIPAL', 'RENEW_ALL')),
    CONSTRAINT ck_settlements_amounts CHECK (principal > 0 AND interest >= 0)
);
