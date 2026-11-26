package vn.nvest.savings.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * MANDATORY: bắt buộc được gọi BÊN TRONG một transaction đang chạy, nếu không sẽ ném lỗi.
     * Đây là chốt chặn để không ai vô tình ghi sự kiện ngoài transaction nghiệp vụ,
     * vì khi đó sự kiện và dữ liệu không còn cùng sống cùng chết.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(String topic, String eventType, String eventId, String aggregateKey, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + eventId, e);
        }
        return repository.insertIfAbsent(eventId, topic, eventType, aggregateKey, json) > 0;
    }
}