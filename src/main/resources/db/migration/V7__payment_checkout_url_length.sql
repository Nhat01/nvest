-- VNPAY checkout URL includes a 128-char HMAC-SHA512 signature, so it can exceed 500 chars.
ALTER TABLE payment_transactions ALTER COLUMN checkout_url TYPE VARCHAR(1000);
