package vn.nvest.savings.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Chỉ để Spring Data có kiểu cho repository; việc ghi đi qua câu lệnh native ở repository. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(ProcessedEvent.Key.class)
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "consumer")
    private String consumer;

    @Id
    @Column(name = "event_id")
    private String eventId;

    public static class Key implements Serializable {
        private String consumer;
        private String eventId;

        public Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(consumer, k.consumer) && Objects.equals(eventId, k.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumer, eventId);
        }
    }
}