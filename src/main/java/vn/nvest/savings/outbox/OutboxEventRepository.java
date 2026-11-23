package vn.nvest.savings.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * ON CONFLICT DO NOTHING: ghi trùng event_id thì bỏ qua, KHÔNG ném lỗi.
     * Nếu ném lỗi vi phạm unique, cả transaction chuyển tiền sẽ bị hủy chỉ vì một sự kiện trùng.
     */
    @Modifying
    @Query(value = """
            INSERT INTO outbox_events (event_id, topic, event_type, aggregate_key, payload)
            VALUES (:eventId, :topic, :eventType, :aggregateKey, :payload)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId, @Param("topic") String topic,
                       @Param("eventType") String eventType, @Param("aggregateKey") String aggregateKey,
                       @Param("payload") String payload);

    /**
     * Khóa các dòng chưa gửi. SKIP LOCKED: dòng đang bị relay khác khóa thì bỏ qua thay vì chờ,
     * nên chạy nhiều instance mà không gửi trùng và không chặn nhau.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("limit") int limit);

    long countByPublishedAtIsNull();
}