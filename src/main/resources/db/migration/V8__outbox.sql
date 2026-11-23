-- V7: Transactional outbox. Sự kiện được ghi CÙNG transaction với nghiệp vụ, rồi relay mới gửi lên Kafka.
CREATE TABLE outbox_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      VARCHAR(100) NOT NULL,     -- id nghiệp vụ duy nhất, consumer dùng để khử trùng
    topic         VARCHAR(100) NOT NULL,
    event_type    VARCHAR(60)  NOT NULL,
    aggregate_key VARCHAR(100) NOT NULL,     -- khóa partition: cùng key thì cùng partition, giữ thứ tự
    payload       TEXT         NOT NULL,     -- JSON
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    CONSTRAINT ux_outbox_event_id UNIQUE (event_id)
);
-- Relay chỉ quét sự kiện chưa gửi, nên dùng chỉ mục một phần cho nhỏ và nhanh
CREATE INDEX ix_outbox_pending ON outbox_events (id) WHERE published_at IS NULL;