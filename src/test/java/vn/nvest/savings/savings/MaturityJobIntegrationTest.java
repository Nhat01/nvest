package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class MaturityJobIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MaturityJob maturityJob;

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private SavingsAccountRepository accountRepository;

    private SavingsAccount open(long userId, String product, long amount, MaturityOption option) {
        return savingsService.open(userId, product, amount, option, "open:" + UUID.randomUUID());
    }

    private SavingsAccount reload(SavingsAccount account) {
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    @Test
    @DisplayName("Job chỉ quyết toán gói đã đến hạn, gói chưa đến hạn vẫn ACTIVE")
    void jobSettlesOnlyDueAccounts() {
        long userId = createUserWithDeposit(12_000_000);
        SavingsAccount due = open(userId, "SAVE_90", 10_000_000, MaturityOption.PAYOUT_ALL);
        SavingsAccount notDue = open(userId, "LONG_180", 2_000_000, MaturityOption.PAYOUT_ALL);

        MaturityJob.RunResult result = maturityJob.runFor(due.getMaturityDate());

        assertThat(result.settled()).isGreaterThanOrEqualTo(1);
        assertThat(reload(due).getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(reload(notDue).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_147_945);
    }

    @Test
    @DisplayName("Chạy lại job không trả tiền lần hai")
    void rerunDoesNotPayTwice() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.PAYOUT_ALL);

        maturityJob.runFor(account.getMaturityDate());
        maturityJob.runFor(account.getMaturityDate());

        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_147_945);
    }

    @Test
    @DisplayName("R19, R49: job lỡ 100 ngày thì xử lý bù cả chuỗi kỳ 1 và kỳ 2, dừng ở kỳ 3 chưa đến hạn")
    void jobCatchesUpOverMissedCycles() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount first = open(userId, "SAVE_90", 10_000_000, MaturityOption.RENEW_PRINCIPAL);

        maturityJob.runFor(first.getMaturityDate().plusDays(100));

        List<SavingsAccount> chain = accountRepository.findByUserIdOrderByIdDesc(userId);   // mới nhất trước
        assertThat(chain).hasSize(3);
        assertThat(chain).extracting(SavingsAccount::getCycleNo).containsExactly(3, 2, 1);
        assertThat(chain).extracting(SavingsAccount::getStatus)
                .containsExactly(AccountStatus.ACTIVE, AccountStatus.RENEWED, AccountStatus.RENEWED);
        // Mỗi kỳ trả đúng 147.945đ lãi, không tính thêm cho 100 ngày trễ
        assertThat(walletService.getBalance(userId).available()).isEqualTo(2 * 147_945);
        // Kỳ 3 bắt đầu đúng ngày đáo hạn kỳ 2, tức kỳ 1 + 90 ngày
        assertThat(chain.get(0).getStartDate()).isEqualTo(first.getMaturityDate().plusDays(90));
    }
}