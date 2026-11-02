package vn.nvest.savings.savings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.InsufficientFundsException;
import vn.nvest.savings.common.ProductNotAvailableException;
import vn.nvest.savings.common.ProductNotFoundException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavingsServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private SavingsAccountRepository accountRepository;

    @Autowired
    private Clock clock;

    private static String newKey() {
        return "open:" + UUID.randomUUID();
    }

    private long custodyBalance() {
        Long balance = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE wallet_type = 'PLATFORM_SAVINGS_CUSTODY'",
                Long.class);
        return balance;
    }

    @Test
    @DisplayName("Mở gói: chốt lãi suất và kỳ hạn, tính ngày đáo hạn, chuyển gốc sang ví custody")
    void openCreatesAccountAndMovesPrincipal() {
        long userId = createUserWithDeposit(10_000_000);
        long custodyBefore = custodyBalance();

        SavingsAccount account = savingsService.open(
                userId, "SAVE_90", 5_000_000, MaturityOption.PAYOUT_ALL, newKey());

        LocalDate today = LocalDate.now(clock);
        assertThat(account.getId()).isNotNull();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getPrincipal()).isEqualTo(5_000_000);
        assertThat(account.getAnnualRate()).isEqualByComparingTo("0.06");
        assertThat(account.getTermDays()).isEqualTo(90);
        assertThat(account.getStartDate()).isEqualTo(today);
        assertThat(account.getMaturityDate()).isEqualTo(today.plusDays(90));
        assertThat(walletService.getBalance(userId).available()).isEqualTo(5_000_000);
        assertThat(custodyBalance() - custodyBefore).isEqualTo(5_000_000);
    }

    @Test
    @DisplayName("R5: admin đổi lãi suất sản phẩm không ảnh hưởng gói đã mở")
    void accountKeepsItsRateWhenProductRateChanges() {
        long userId = createUserWithDeposit(5_000_000);
        SavingsAccount account = savingsService.open(
                userId, "SAVE_90", 2_000_000, MaturityOption.PAYOUT_ALL, newKey());
        try {
            jdbc.update("UPDATE savings_products SET annual_rate = 0.0500 WHERE code = 'SAVE_90'");

            SavingsAccount reloaded = accountRepository.findById(account.getId()).orElseThrow();
            assertThat(reloaded.getAnnualRate()).isEqualByComparingTo("0.06");
        } finally {
            jdbc.update("UPDATE savings_products SET annual_rate = 0.0600 WHERE code = 'SAVE_90'");
        }
    }

    @Test
    @DisplayName("R13: không đủ tiền thì hủy sạch, không để lại gói hay biến động ví nào")
    void openWithInsufficientFundsLeavesNothingBehind() {
        long userId = createUserWithDeposit(1_000_000);
        String key = newKey();

        assertThatThrownBy(() -> savingsService.open(
                userId, "SAVE_90", 2_000_000, MaturityOption.PAYOUT_ALL, key))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(savingsService.listByUser(userId)).isEmpty();
        assertThat(accountRepository.findByIdempotencyKey(key)).isEmpty();
        assertThat(walletService.getBalance(userId).available()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("R3: số tiền ngoài khoảng của sản phẩm bị từ chối")
    void amountOutsideProductRangeIsRejected() {
        long userId = createUserWithDeposit(0);

        assertThatThrownBy(() -> savingsService.open(
                userId, "SAVE_90", 999_999, MaturityOption.PAYOUT_ALL, newKey()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> savingsService.open(
                userId, "SAVE_90", 500_000_001, MaturityOption.PAYOUT_ALL, newKey()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Sản phẩm không tồn tại hoặc đã ngừng bán bị từ chối")
    void unknownOrStoppedProductIsRejected() {
        long userId = createUserWithDeposit(5_000_000);

        assertThatThrownBy(() -> savingsService.open(
                userId, "NO_SUCH", 1_000_000, MaturityOption.PAYOUT_ALL, newKey()))
                .isInstanceOf(ProductNotFoundException.class);

        try {
            jdbc.update("UPDATE savings_products SET status = 'STOPPED' WHERE code = 'FLEX_30'");
            assertThatThrownBy(() -> savingsService.open(
                    userId, "FLEX_30", 1_000_000, MaturityOption.PAYOUT_ALL, newKey()))
                    .isInstanceOf(ProductNotAvailableException.class);
        } finally {
            jdbc.update("UPDATE savings_products SET status = 'ON_SALE' WHERE code = 'FLEX_30'");
        }
    }

    @Test
    @DisplayName("R38: gửi lại cùng key thì trả gói cũ và chỉ trừ tiền một lần")
    void sameKeyReturnsSameAccount() {
        long userId = createUserWithDeposit(10_000_000);
        String key = newKey();

        SavingsAccount first = savingsService.open(
                userId, "SAVE_90", 3_000_000, MaturityOption.PAYOUT_ALL, key);
        SavingsAccount second = savingsService.open(
                userId, "SAVE_90", 3_000_000, MaturityOption.PAYOUT_ALL, key);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(savingsService.listByUser(userId)).hasSize(1);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(7_000_000);

        assertThatThrownBy(() -> savingsService.open(
                userId, "SAVE_90", 4_000_000, MaturityOption.PAYOUT_ALL, key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("R15: một user mở được nhiều gói cùng lúc")
    void userCanHoldSeveralAccounts() {
        long userId = createUserWithDeposit(10_000_000);

        savingsService.open(userId, "SAVE_90", 3_000_000, MaturityOption.PAYOUT_ALL, newKey());
        savingsService.open(userId, "LONG_180", 2_000_000, MaturityOption.RENEW_ALL, newKey());

        assertThat(savingsService.listByUser(userId)).hasSize(2);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(5_000_000);
    }
}
