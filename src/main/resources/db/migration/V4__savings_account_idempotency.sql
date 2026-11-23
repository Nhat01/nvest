-- V4: Khoa chong mo trung goi tiet kiem khi client gui lai cung mot yeu cau.
-- Goi tai tuc do he thong tao se dung khoa dang 'renew:<id goi cu>' (Buoc 7).
--
-- Them cot theo 3 buoc de nang cap duoc ca DB da co du lieu:
--   1. them cot cho phep NULL
--   2. backfill gia tri duy nhat cho cac dong cu
--   3. siet NOT NULL
-- Neu them truc tiep NOT NULL tren bang co dong, PostgreSQL bao
-- "column ... contains null values" va Flyway dung giua chung.
ALTER TABLE savings_accounts ADD COLUMN idempotency_key VARCHAR(100);

UPDATE savings_accounts
SET idempotency_key = 'legacy:' || id
WHERE idempotency_key IS NULL;

ALTER TABLE savings_accounts ALTER COLUMN idempotency_key SET NOT NULL;

ALTER TABLE savings_accounts
    ADD CONSTRAINT ux_savings_accounts_idempotency UNIQUE (idempotency_key);
