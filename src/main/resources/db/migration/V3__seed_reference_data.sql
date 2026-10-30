-- V3: Dữ liệu tham chiếu: 3 ví hệ thống và 3 sản phẩm ban đầu (R4)

INSERT INTO wallets (wallet_type) VALUES
    ('PLATFORM_EXTERNAL'),
    ('PLATFORM_SAVINGS_CUSTODY'),
    ('PLATFORM_INTEREST_POOL');

INSERT INTO savings_products (code, name, term_days, annual_rate, min_amount, max_amount) VALUES
    ('FLEX_30',  'Linh hoat 30 ngay', 30,  0.0450, 1000000, 500000000),
    ('SAVE_90',  'Tich luy 90 ngay',  90,  0.0600, 1000000, 500000000),
    ('LONG_180', 'Dai han 180 ngay',  180, 0.0700, 1000000, 500000000);
