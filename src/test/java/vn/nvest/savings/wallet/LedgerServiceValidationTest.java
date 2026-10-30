package vn.nvest.savings.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.wallet.BalanceType;
import vn.nvest.savings.wallet.LedgerLine;
import vn.nvest.savings.wallet.LedgerService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceValidationTest {

    private static final LedgerLine OUT = new LedgerLine(1L, BalanceType.AVAILABLE, -500);
    private static final LedgerLine IN = new LedgerLine(2L, BalanceType.AVAILABLE, 500);

    @Test
    @DisplayName("Giao dịch cân bằng (tổng bằng 0) hợp lệ")
    void balancedIsValid() {
        assertThatCode(() -> LedgerService.validate("key-1", List.of(OUT, IN)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R37: tổng khác 0 bị từ chối")
    void unbalancedIsRejected() {
        LedgerLine wrongIn = new LedgerLine(2L, BalanceType.AVAILABLE, 400);
        assertThatThrownBy(() -> LedgerService.validate("key-1", List.of(OUT, wrongIn)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Dòng có amount = 0 bị từ chối")
    void zeroAmountIsRejected() {
        LedgerLine zero = new LedgerLine(2L, BalanceType.AVAILABLE, 0);
        assertThatThrownBy(() -> LedgerService.validate("key-1", List.of(OUT, zero)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R35: thiếu idempotencyKey bị từ chối")
    void blankKeyIsRejected() {
        assertThatThrownBy(() -> LedgerService.validate(" ", List.of(OUT, IN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerService.validate(null, List.of(OUT, IN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Chỉ một dòng thì không hợp lệ")
    void singleLineIsRejected() {
        assertThatThrownBy(() -> LedgerService.validate("key-1", List.of(OUT)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}