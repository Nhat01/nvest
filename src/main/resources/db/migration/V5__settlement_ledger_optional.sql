-- V5: Quyet toan khong co tien nao di chuyen (vd. lai suat 0% va tai tuc) thi khong ghi so cai.
-- Khi do settlements.ledger_transaction_id de trong (R46).
ALTER TABLE settlements ALTER COLUMN ledger_transaction_id DROP NOT NULL;
