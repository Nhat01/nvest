package vn.nvest.savings.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.nvest.savings.outbox.EventTypes;
import vn.nvest.savings.outbox.SavingsMaturityReminderEvent;
import vn.nvest.savings.user.EmailSender;
import vn.nvest.savings.user.User;
import vn.nvest.savings.user.UserRepository;
import vn.nvest.savings.user.UserStatus;

@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    static final String CONSUMER = "email";

    private final ProcessedEventRepository processedRepository;
    private final UserRepository userRepository;
    private final EmailSender emailSender;
    private final ObjectMapper objectMapper;

    public EmailNotificationService(ProcessedEventRepository processedRepository,
                                  UserRepository userRepository,
                                  EmailSender emailSender,
                                  ObjectMapper objectMapper) {
        this.processedRepository = processedRepository;
        this.userRepository = userRepository;
        this.emailSender = emailSender;
        this.objectMapper = objectMapper;
    }

    /**
     * Xử lý sự kiện gửi email nhắc đáo hạn.
     * Đánh dấu đã xử lý SAU KHI gửi thành công để đảm bảo at-least-once.
     * Mặc định gửi cho mọi user đã xác thực email.
     *
     * @return true nếu đã gửi email thành công
     */
    public boolean handle(String eventId, String eventType, String payload) {
        if (!EventTypes.SAVINGS_MATURITY_REMINDER.equals(eventType)) {
            log.info("Ignoring non-reminder event type {} for email consumer ({})", eventType, eventId);
            return false;
        }

        if (processedRepository.existsByConsumerAndEventId(CONSUMER, eventId)) {
            log.info("Duplicate event {} skipped for email", eventId);
            return false;
        }

        SavingsMaturityReminderEvent event;
        try {
            event = objectMapper.readValue(payload, SavingsMaturityReminderEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed " + eventType + " payload", e);
        }

        User user = userRepository.findById(event.userId()).orElse(null);
        if (user == null) {
            log.warn("User {} not found for event {}, skipping email", event.userId(), eventId);
            processedRepository.markProcessed(CONSUMER, eventId);
            return false;
        }

        // Hiện chưa có bảng tùy chọn nhận thông báo của user, nên mặc định gửi cho mọi user đã xác thực email.
        if (!user.isEmailVerified() || user.getStatus() != UserStatus.ACTIVE) {
            log.info("User {} is not eligible for reminder email (account #{})", user.getId(), event.accountId());
            processedRepository.markProcessed(CONSUMER, eventId);
            return false;
        }

        String message = NotificationService.reminderMessage(event);
        emailSender.sendMaturityReminder(user.getEmail(), user.getFullName(), event.accountId(), message);

        // Đánh dấu đã xử lý SAU KHI gửi thành công, đảm bảo at-least-once
        processedRepository.markProcessed(CONSUMER, eventId);
        return true;
    }
}
