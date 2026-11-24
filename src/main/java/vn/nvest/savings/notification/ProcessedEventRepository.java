package vn.nvest.savings.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    /**
     * Trả 1 nếu đây là lần đầu thấy sự kiện, 0 nếu đã xử lý rồi.
     * Hai luồng chèn cùng khóa đồng thời: luồng sau chờ luồng trước commit rồi nhận 0.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_events (consumer, event_id) VALUES (:consumer, :eventId) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int markProcessed(@Param("consumer") String consumer, @Param("eventId") String eventId);
}