package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.common.AccountNotFoundException;
import vn.nvest.savings.common.InvalidStateTransitionException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class SavingsOptionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private MaturityService maturityService;

    @Autowired
    private SavingsAccountRepository accountRepository;

    private SavingsAccount openPayoutAll(long userId) {
        return savingsService.open(userId, "SAVE_90", 10_000_000,
                MaturityOption.PAYOUT_ALL, "open:" + UUID.randomUUID());
    }

    @Test
    @DisplayName("R16: đổi tùy chọn trước hạn thì lúc đáo hạn áp dụng tùy chọn mới")
    void changedOptionIsAppliedAtMaturity() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openPayoutAll(userId);

        savingsService.changeMaturityOption(userId, account.getId(), MaturityOption.RENEW_ALL);
        Settlement settlement = maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();

        assertThat(settlement.getOptionApplied()).isEqualTo(MaturityOption.RENEW_ALL);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.RENEWED);
        assertThat(accountRepository.findById(settlement.getNewAccountId()).orElseThrow().getPrincipal())
                .isEqualTo(10_147_945);
    }

    @Test
    @DisplayName("Gói của người khác hoặc không tồn tại đều báo không tìm thấy")
    void foreignOrUnknownAccountIsNotFound() {
        long owner = createUserWithDeposit(10_000_000);
        long stranger = createUserWithDeposit(0);
        SavingsAccount account = openPayoutAll(owner);

        assertThatThrownBy(() -> savingsService.changeMaturityOption(
                stranger, account.getId(), MaturityOption.RENEW_ALL))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> savingsService.changeMaturityOption(
                owner, 999_999_999L, MaturityOption.RENEW_ALL))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getMaturityOption())
                .isEqualTo(MaturityOption.PAYOUT_ALL);   // không bị đổi
    }

    @Test
    @DisplayName("R16, R48: đã đến ngày đáo hạn thì không đổi được nữa")
    void cannotChangeOnOrAfterMaturityDate() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openPayoutAll(userId);
        // Dời cả ngày bắt đầu và đáo hạn lùi 100 ngày: hôm nay đã quá hạn, ràng buộc R9 vẫn thỏa
        jdbc.update("UPDATE savings_accounts SET start_date = start_date - 100, "
                + "maturity_date = maturity_date - 100 WHERE id = ?", account.getId());

        assertThatThrownBy(() -> savingsService.changeMaturityOption(
                userId, account.getId(), MaturityOption.RENEW_ALL))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("R48: gói đã quyết toán thì không đổi được")
    void cannotChangeAfterSettlement() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openPayoutAll(userId);
        maturityService.settle(account.getId(), account.getMaturityDate());

        assertThatThrownBy(() -> savingsService.changeMaturityOption(
                userId, account.getId(), MaturityOption.RENEW_ALL))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}