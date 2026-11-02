package vn.nvest.savings.savings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.common.InvalidStateTransitionException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class SavingsAccountTest {

    private static SavingsProduct save90() {
        return new SavingsProduct("SAVE_90", "Tich luy 90 ngay", 90,
                new BigDecimal("0.0600"), 1_000_000, 500_000_000);
    }

    private static SavingsAccount newAccount() {
        return SavingsAccount.open(1L, save90(), 10_000_000, MaturityOption.PAYOUT_ALL,
                LocalDate.of(2026, 11, 1), "key-1");
    }

    @Test
    @DisplayName("R5, R9: gói chép lãi suất, kỳ hạn từ sản phẩm và tính đúng ngày đáo hạn")
    void openCopiesRateAndComputesMaturity() {
        SavingsAccount account = newAccount();

        assertThat(account.getAnnualRate()).isEqualByComparingTo("0.06");
        assertThat(account.getTermDays()).isEqualTo(90);
        assertThat(account.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(account.getMaturityDate()).isEqualTo(LocalDate.of(2027, 1, 30));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getCycleNo()).isEqualTo(1);
        assertThat(account.getPrincipal()).isEqualTo(10_000_000);
    }

    @Test
    @DisplayName("Chuyển trạng thái hợp lệ")
    void validTransition() {
        SavingsAccount account = newAccount();

        account.transitionTo(AccountStatus.MATURED);
        account.transitionTo(AccountStatus.RENEWED);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.RENEWED);
    }

    @Test
    @DisplayName("Chuyển trạng thái sai đường bị từ chối và trạng thái giữ nguyên")
    void invalidTransition() {
        SavingsAccount account = newAccount();

        assertThatThrownBy(() -> account.transitionTo(AccountStatus.CLOSED))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("R3: số tiền ngoài khoảng cho phép bị từ chối, biên thì chấp nhận")
    void amountRange() {
        SavingsProduct product = save90();

        assertThatCode(() -> product.validateAmount(1_000_000)).doesNotThrowAnyException();
        assertThatCode(() -> product.validateAmount(500_000_000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> product.validateAmount(999_999))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.validateAmount(500_000_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
