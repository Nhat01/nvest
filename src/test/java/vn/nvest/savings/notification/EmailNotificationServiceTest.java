package vn.nvest.savings.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.nvest.savings.outbox.EventTypes;
import vn.nvest.savings.user.EmailSender;
import vn.nvest.savings.user.User;
import vn.nvest.savings.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private ProcessedEventRepository processedRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailSender emailSender;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(processedRepository, userRepository, emailSender, objectMapper);
    }

    private static String reminderPayload(long userId, long accountId) {
        return "{\"accountId\":" + accountId + ",\"userId\":" + userId + ",\"principal\":10000000,"
                + "\"expectedInterest\":147945,\"maturityDate\":\"2027-01-30\",\"daysLeft\":3,"
                + "\"maturityOption\":\"RENEW_ALL\",\"productOnSale\":true}";
    }

    @Test
    @DisplayName("Gửi email và đánh dấu processed sau khi gửi thành công cho user đã xác thực email")
    void sendsEmailAndMarksProcessed_whenEmailVerified() {
        long userId = 10L;
        long accountId = 100L;
        String eventId = "evt-1";
        User user = User.register("user@test.local", "hash", "Nguyen Van An");
        user.markEmailVerified(Instant.now());

        when(processedRepository.existsByConsumerAndEventId("email", eventId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        boolean handled = service.handle(eventId, EventTypes.SAVINGS_MATURITY_REMINDER, reminderPayload(userId, accountId));

        assertThat(handled).isTrue();
        verify(emailSender).sendMaturityReminder(eq("user@test.local"), eq("Nguyen Van An"), eq(accountId), anyString());
        verify(processedRepository).markProcessed("email", eventId);
    }

    @Test
    @DisplayName("Bỏ qua gửi email nhưng vẫn đánh dấu processed khi user chưa xác thực email")
    void skipsEmailAndMarksProcessed_whenEmailNotVerified() {
        long userId = 10L;
        long accountId = 100L;
        String eventId = "evt-2";
        User user = User.register("unverified@test.local", "hash", "Nguyen Van B");
        // user chưa xác thực email (emailVerifiedAt == null)

        when(processedRepository.existsByConsumerAndEventId("email", eventId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        boolean handled = service.handle(eventId, EventTypes.SAVINGS_MATURITY_REMINDER, reminderPayload(userId, accountId));

        assertThat(handled).isFalse();
        verify(emailSender, never()).sendMaturityReminder(anyString(), anyString(), anyLong(), anyString());
        verify(processedRepository).markProcessed("email", eventId);
    }

    @Test
    @DisplayName("Sự kiện trùng đã xử lý thì bỏ qua ngay, không gửi email lại")
    void skips_whenAlreadyProcessed() {
        String eventId = "evt-dup";
        when(processedRepository.existsByConsumerAndEventId("email", eventId)).thenReturn(true);

        boolean handled = service.handle(eventId, EventTypes.SAVINGS_MATURITY_REMINDER, reminderPayload(1L, 1L));

        assertThat(handled).isFalse();
        verify(emailSender, never()).sendMaturityReminder(anyString(), anyString(), anyLong(), anyString());
        verify(processedRepository, never()).markProcessed(anyString(), anyString());
    }

    @Test
    @DisplayName("Gửi email thất bại thì ném ngoại lệ và KHÔNG đánh dấu processed (at-least-once)")
    void doesNotMarkProcessed_whenSendingFails() {
        long userId = 10L;
        long accountId = 100L;
        String eventId = "evt-fail";
        User user = User.register("user@test.local", "hash", "Nguyen Van An");
        user.markEmailVerified(Instant.now());

        when(processedRepository.existsByConsumerAndEventId("email", eventId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("SMTP connection timeout")).when(emailSender)
                .sendMaturityReminder(anyString(), anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> service.handle(eventId, EventTypes.SAVINGS_MATURITY_REMINDER, reminderPayload(userId, accountId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SMTP connection timeout");

        verify(processedRepository, never()).markProcessed("email", eventId);
    }

    @Test
    @DisplayName("Loại sự kiện không phải nhắc đáo hạn thì bỏ qua")
    void ignoresNonReminderEventType() {
        boolean handled = service.handle("evt-other", EventTypes.DEPOSIT_SUCCEEDED, "{}");

        assertThat(handled).isFalse();
        verify(emailSender, never()).sendMaturityReminder(any(), any(), anyLong(), any());
        verify(processedRepository, never()).markProcessed(any(), any());
    }
}
