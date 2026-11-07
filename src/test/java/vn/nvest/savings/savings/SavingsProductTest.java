package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SavingsProductTest {

    private static SavingsProduct product() {
        return new SavingsProduct("SAVE_90", "Tich luy 90 ngay", 90,
                new BigDecimal("0.0600"), 1_000_000, 500_000_000);
    }

    @Test
    @DisplayName("R36: đổi lãi suất trong khoảng 0 đến 20% thì được, kể cả hai biên")
    void validRatesAreAccepted() {
        SavingsProduct product = product();

        assertThatCode(() -> product.changeAnnualRate(BigDecimal.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> product.changeAnnualRate(new BigDecimal("0.2000"))).doesNotThrowAnyException();
        product.changeAnnualRate(new BigDecimal("0.0625"));

        assertThat(product.getAnnualRate()).isEqualByComparingTo("0.0625");
    }

    @Test
    @DisplayName("R36: lãi suất âm, vượt 20% hoặc null bị từ chối và giá trị cũ giữ nguyên")
    void invalidRatesAreRejected() {
        SavingsProduct product = product();

        assertThatThrownBy(() -> product.changeAnnualRate(new BigDecimal("-0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.changeAnnualRate(new BigDecimal("0.2001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.changeAnnualRate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getAnnualRate()).isEqualByComparingTo("0.06");
    }

    @Test
    @DisplayName("Ngừng bán làm sản phẩm không còn bán")
    void stopSaleMakesTheProductUnavailable() {
        SavingsProduct product = product();

        product.stopSale();

        assertThat(product.isOnSale()).isFalse();
    }
}