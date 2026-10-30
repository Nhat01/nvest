package vn.nvest.savings.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.common.InsufficientFundsException;
import vn.nvest.savings.wallet.BalanceType;
import vn.nvest.savings.wallet.Wallet;
import vn.nvest.savings.wallet.WalletType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    @Test
    @DisplayName("Ví user không được âm, và số dư giữ nguyên khi bị từ chối")
    void userWalletCannotGoNegative() {
        Wallet wallet = Wallet.forUser(1L);
        wallet.apply(BalanceType.AVAILABLE, 100);

        assertThatThrownBy(() -> wallet.apply(BalanceType.AVAILABLE, -101))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(wallet.getAvailableBalance()).isEqualTo(100);
    }

    @Test
    @DisplayName("R37: ví hệ thống được phép âm")
    void systemWalletMayGoNegative() {
        Wallet external = Wallet.system(WalletType.PLATFORM_EXTERNAL);
        external.apply(BalanceType.AVAILABLE, -100);

        assertThat(external.getAvailableBalance()).isEqualTo(-100);
    }

    @Test
    @DisplayName("AVAILABLE và HELD là hai ngăn độc lập")
    void balanceTypesAreIndependent() {
        Wallet wallet = Wallet.forUser(1L);
        wallet.apply(BalanceType.AVAILABLE, 100);
        wallet.apply(BalanceType.AVAILABLE, -30);
        wallet.apply(BalanceType.HELD, 30);

        assertThat(wallet.getAvailableBalance()).isEqualTo(70);
        assertThat(wallet.getHeldBalance()).isEqualTo(30);
    }

    @Test
    @DisplayName("Ví hệ thống không tạo bằng loại USER")
    void systemFactoryRejectsUserType() {
        assertThatThrownBy(() -> Wallet.system(WalletType.USER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
