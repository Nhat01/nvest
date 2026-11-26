package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.notification.NotificationService;
import vn.nvest.savings.outbox.EventTypes;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class MaturityReminderIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MaturityReminderService reminderService;
    @Autowired private MaturityReminderJob reminderJob;
    @Autowired private SavingsService savingsService;
    @Autowired private EarlyWithdrawalService earlyWithdrawalService;
    @Autowired private NotificationService notificationService;
    @Autowired private Clock clock;

    private SavingsAccount openAccount(long userId) {
        return savingsService.open(userId, "SAVE_90", 10_000_000,
                MaturityOption.RENEW_ALL, "open:" + UUID.randomUUID());
    }

    /** Dời cả ngày bắt đầu và đáo hạn lùi `days` ngày. Gói mở hôm nay có 90 ngày, lùi 87 thì còn 3. */
    private void travelBack(long accountId, int days) {
        jdbc.update("UPDATE savings_accounts SET start_date = start_date - " + days
                + ", maturity_date = maturity_date - " + days + " WHERE id = ?", accountId);
    }

    private long events(long accountId, int milestone) {
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_id = ?",
                Long.class, "maturity-reminder:" + accountId + ":" + milestone);
        return rows;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    @Test
    @DisplayName("R82, R83: còn 3 ngày thì nhắc một lần; chạy lại hoặc job lỡ một ngày (còn 2) không nhắc trùng")
    void firstMilestoneIsSentOnce() {
        SavingsAccount account = openAccount(createUserWithDeposit(10_000_000));
        travelBack(account.getId(), 87);

        assertThat(reminderService.remind(account.getId(), today())).isTrue();
        assertThat(reminderService.remind(account.getId(), today())).isFalse();
        assertThat(events(account.getId(), 3)).isEqualTo(1);

        travelBack(account.getId(), 1);   // còn 2 ngày: vẫn thuộc mốc 3, đã nhắc rồi
        assertThat(reminderService.remind(account.getId(), today())).isFalse();
        assertThat(events(account.getId(), 3)).isEqualTo(1);
    }

    @Test
    @DisplayName("R82: còn 1 ngày thì có thêm lời nhắc mốc cuối, nội dung đúng")
    void finalMilestoneIsSeparate() {
        SavingsAccount account = openAccount(createUserWithDeposit(10_000_000));
        travelBack(account.getId(), 87);
        reminderService.remind(account.getId(), today());
        travelBack(account.getId(), 2);   // còn 1 ngày

        assertThat(reminderService.remind(account.getId(), today())).isTrue();

        assertThat(events(account.getId(), 1)).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_id = ?",
                String.class, "maturity-reminder:" + account.getId() + ":1");
        assertThat(payload).contains("\"daysLeft\":1").contains("\"expectedInterest\":147945")
                .contains("\"maturityOption\":\"RENEW_ALL\"").contains("\"productOnSale\":true");
    }

    @Test
    @DisplayName("Ngoài khoảng 1-3 ngày thì không nhắc: còn 90, còn 4, đáo hạn hôm nay")
    void outsideTheWindowIsIgnored() {
        long userId = createUserWithDeposit(30_000_000);
        SavingsAccount fresh = openAccount(userId);
        SavingsAccount fourDays = openAccount(userId);
        SavingsAccount dueToday = openAccount(userId);
        travelBack(fourDays.getId(), 86);
        travelBack(dueToday.getId(), 90);

        assertThat(reminderService.remind(fresh.getId(), today())).isFalse();
        assertThat(reminderService.remind(fourDays.getId(), today())).isFalse();
        assertThat(reminderService.remind(dueToday.getId(), today())).isFalse();
    }

    @Test
    @DisplayName("Gói đã rút sớm (không còn ACTIVE) thì không nhắc")
    void nonActiveAccountIsNotReminded() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openAccount(userId);
        travelBack(account.getId(), 87);
        earlyWithdrawalService.withdraw(userId, account.getId());

        assertThat(reminderService.remind(account.getId(), today())).isFalse();
        assertThat(events(account.getId(), 3)).isZero();
    }

    @Test
    @DisplayName("Job quét các gói trong khoảng 3 ngày tới, chạy lại không tạo thêm")
    void jobIsIdempotent() {
        SavingsAccount account = openAccount(createUserWithDeposit(10_000_000));
        travelBack(account.getId(), 87);

        MaturityReminderJob.RunResult first = reminderJob.runFor(today());
        MaturityReminderJob.RunResult second = reminderJob.runFor(today());

        assertThat(first.failures()).isZero();
        assertThat(first.sent()).isGreaterThanOrEqualTo(1);
        assertThat(second.sent()).isZero();
        assertThat(events(account.getId(), 3)).isEqualTo(1);
    }

    @Test
    @DisplayName("Consumer: tạo thông báo có ngày, gốc, lãi dự kiến, tùy chọn và lời nhắc đổi")
    void notificationMessageIsBuilt() {
        long userId = createUserWithDeposit(0);
        String payload = "{\"accountId\":7,\"userId\":" + userId + ",\"principal\":10000000,"
                + "\"expectedInterest\":147945,\"maturityDate\":\"2027-01-30\",\"daysLeft\":3,"
                + "\"maturityOption\":\"RENEW_ALL\",\"productOnSale\":true}";

        assertThat(notificationService.handle("evt:" + UUID.randomUUID(),
                EventTypes.SAVINGS_MATURITY_REMINDER, payload)).isTrue();

        String message = jdbc.queryForObject(
                "SELECT message FROM notifications WHERE user_id = ?", String.class, userId);
        assertThat(message).contains("#7").contains("sau 3 ngày").contains("30/01/2027")
                .contains("10.000.000đ").contains("147.945đ")
                .contains("tái tục cả gốc và lãi").contains("đổi tùy chọn trước ngày đáo hạn")
                .doesNotContain("ngừng bán");
    }

    @Test
    @DisplayName("R18: sản phẩm ngừng bán mà đang chọn tái tục thì thông báo cảnh báo; còn 1 ngày ghi 'ngày mai'")
    void stoppedProductWarning() {
        long userId = createUserWithDeposit(0);
        String payload = "{\"accountId\":8,\"userId\":" + userId + ",\"principal\":5000000,"
                + "\"expectedInterest\":18493,\"maturityDate\":\"2027-01-30\",\"daysLeft\":1,"
                + "\"maturityOption\":\"RENEW_PRINCIPAL\",\"productOnSale\":false}";

        notificationService.handle("evt:" + UUID.randomUUID(), EventTypes.SAVINGS_MATURITY_REMINDER, payload);

        String message = jdbc.queryForObject(
                "SELECT message FROM notifications WHERE user_id = ?", String.class, userId);
        assertThat(message).contains("ngày mai").contains("ngừng bán").contains("trả hết về ví");
    }
}