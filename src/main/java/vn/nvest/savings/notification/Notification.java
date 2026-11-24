package vn.nvest.savings.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "message", nullable = false, updatable = false)
    private String message;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Notification(Long userId, String type, String message, String eventId) {
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.eventId = eventId;
    }
}