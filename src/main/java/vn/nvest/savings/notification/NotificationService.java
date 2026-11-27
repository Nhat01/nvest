package vn.nvest.savings.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.outbox.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final String CONSUMER = "notification";
    private static final Locale VI = Locale.of("vi", "VN");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private record Draft(long userId, String message) {
    }

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedRepository;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository notificationRepository,
                               ProcessedEventRepository processedRepository,
                               ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.processedRepository = processedRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Xử lý MỘT sự kiện. Đánh dấu "đã xử lý" và tạo thông báo nằm trong CÙNG một transaction:
     * hoặc cả hai cùng có, hoặc cả hai cùng không. Gọi lại với cùng eventId thì không làm gì.
     *
     * @return true nếu đã tạo thông báo mới
     */
    @Transactional
    public boolean handle(String eventId, String eventType, String payload) {
        Draft draft = draftFor(eventType, payload);
        if (draft == null) {
            // Loại sự kiện chưa biết (producer mới hơn consumer): bỏ qua, không được làm hỏng cả luồng
            log.info("Ignoring unknown event type {} ({})", eventType, eventId);
            return false;
        }
        if (processedRepository.markProcessed(CONSUMER, eventId) == 0) {
            log.info("Duplicate event {} skipped", eventId);
            return false;
        }
        notificationRepository.save(new Notification(draft.userId(), eventType, draft.message(), eventId));
        return true;
    }

    @Transactional(readOnly = true)
    public List<Notification> list(long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return notificationRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(Math.max(page, 0), safeSize));
    }

    private Draft draftFor(String eventType, String payload) {
        try {
            return switch (eventType) {
                case EventTypes.DEPOSIT_SUCCEEDED -> {
                    DepositSucceededEvent e = objectMapper.readValue(payload, DepositSucceededEvent.class);
                    yield new Draft(e.userId(), "Nạp thành công " + vnd(e.amount()) + " vào ví.");
                }
                case EventTypes.WITHDRAWAL_SETTLED -> {
                    WithdrawalSettledEvent e = objectMapper.readValue(payload, WithdrawalSettledEvent.class);
                    yield new Draft(e.userId(), "SUCCEEDED".equals(e.status())
                            ? "Rút " + vnd(e.amount()) + " thành công."
                            : "Lệnh rút " + vnd(e.amount()) + " thất bại, tiền đã hoàn về ví.");
                }
                case EventTypes.SAVINGS_MATURED -> {
                    SavingsMaturedEvent e = objectMapper.readValue(payload, SavingsMaturedEvent.class);
                    yield new Draft(e.userId(), maturityMessage(e));
                }
                case EventTypes.SAVINGS_MATURITY_REMINDER -> {
                    SavingsMaturityReminderEvent e = objectMapper.readValue(payload, SavingsMaturityReminderEvent.class);
                    yield new Draft(e.userId(), reminderMessage(e));
                }
                default -> null;
            };
        } catch (JsonProcessingException e) {
            // Payload hỏng: thử lại bao nhiêu lần cũng vẫn hỏng, nên dùng loại lỗi "không thử lại" (xem KafkaConsumerConfig)
            throw new IllegalArgumentException("Malformed " + eventType + " payload", e);
        }
    }

    static String reminderMessage(SavingsMaturityReminderEvent e) {
        String when = e.daysLeft() == 1 ? "ngày mai" : "sau " + e.daysLeft() + " ngày";
        String option = switch (e.maturityOption()) {
            case "RENEW_PRINCIPAL" -> "tái tục gốc, lãi về ví";
            case "RENEW_ALL" -> "tái tục cả gốc và lãi";
            default -> "nhận gốc và lãi về ví";
        };
        StringBuilder message = new StringBuilder("Gói #").append(e.accountId())
                .append(" đáo hạn ").append(when).append(" (").append(DATE.format(e.maturityDate()))
                .append("): gốc ").append(vnd(e.principal()))
                .append(", lãi dự kiến ").append(vnd(e.expectedInterest()))
                .append(". Hiện chọn: ").append(option)
                .append(". Bạn có thể đổi tùy chọn trước ngày đáo hạn.");
        if (!e.productOnSale() && !"PAYOUT_ALL".equals(e.maturityOption())) {   // R18
            message.append(" Lưu ý: sản phẩm đã ngừng bán nên gói sẽ được trả hết về ví thay vì tái tục.");
        }
        return message.toString();   // tối đa khoảng 350 ký tự, nằm trong VARCHAR(500) của notifications.message
    }

    private static String maturityMessage(SavingsMaturedEvent e) {
        String total = vnd(e.principal() + e.interest());
        if (e.fallbackReason() != null) {   // R18: tự chuyển sang PAYOUT_ALL, đây là điều user cần được báo
            String reason = switch (e.fallbackReason()) {
                case "PRODUCT_NOT_ON_SALE" -> "sản phẩm đã ngừng bán";
                case "AMOUNT_OUT_OF_RANGE" -> "số tiền tái tục vượt hạn mức của sản phẩm";
                default -> e.fallbackReason();
            };
            return "Gói #" + e.accountId() + " đáo hạn nhưng không thể tái tục (" + reason
                    + "). Gốc và lãi " + total + " đã về ví.";
        }
        if (e.newAccountId() != null) {
            return "Gói #" + e.accountId() + " đã tái tục sang kỳ mới (gói #" + e.newAccountId() + ").";
        }
        return "Gói #" + e.accountId() + " đáo hạn: " + total + " đã về ví.";
    }

    private static String vnd(long amount) {
        return NumberFormat.getInstance(VI).format(amount) + "đ";   // NumberFormat không an toàn đa luồng, nên tạo mới mỗi lần
    }
}