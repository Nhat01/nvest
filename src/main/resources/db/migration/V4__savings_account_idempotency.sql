-- V4: Khoa chong mo trung goi tiet kiem khi client gui lai cung mot yeu cau.
-- Goi tai tuc do he thong tao se dung khoa dang 'renew:<id goi cu>' (Buoc 7).
ALTER TABLE savings_accounts ADD COLUMN idempotency_key VARCHAR(100) NOT NULL;
ALTER TABLE savings_accounts
    ADD CONSTRAINT ux_savings_accounts_idempotency UNIQUE (idempotency_key);
