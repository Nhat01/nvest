-- V9: Khử trùng sự kiện cho consumer, và thông báo trong ứng dụng.
CREATE TABLE processed_events (
    consumer     VARCHAR(60)  NOT NULL,
    event_id     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)    -- mỗi consumer khử trùng độc lập
);

CREATE TABLE notifications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    type       VARCHAR(60)  NOT NULL,
    message    VARCHAR(500) NOT NULL,
    event_id   VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, id);