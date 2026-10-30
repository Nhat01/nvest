package vn.nvest.savings.interest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.nvest.savings.interest.InterestCalculator;

class InterestCalculatorTest {

    private static final BigDecimal RATE_6 = new BigDecimal("0.06");

    @Test
    @DisplayName("R8: gốc 10 triệu, 6%, 90 ngày → 147.945đ")
    void fullTermInterest() {
        assertThat(InterestCalculator.accruedInterest(10_000_000L, RATE_6, 90))
                .isEqualTo(147_945L);
    }

    @Test
    @DisplayName("R8: kỳ 2 của RENEW_ALL, gốc 10.147.945đ → 150.133đ")
    void secondCycleOfRenewAll() {
        assertThat(InterestCalculator.accruedInterest(10_147_945L, RATE_6, 90))
                .isEqualTo(150_133L);
    }

    @Test
    @DisplayName("R2: luôn làm tròn xuống")
    void alwaysRoundsDown() {
        // 10.000.000 × 0,06 × 90 / 365 = 147.945,205... → 147.945
        // 10.000.000 × 0,06 × 40 / 365 =  65.753,424... →  65.753
        assertThat(InterestCalculator.accruedInterest(10_000_000L, RATE_6, 40))
                .isEqualTo(65_753L);
    }

    @Test
    @DisplayName("0 ngày hoặc lãi suất 0 → lãi bằng 0")
    void zeroCases() {
        assertThat(InterestCalculator.accruedInterest(10_000_000L, RATE_6, 0)).isZero();
        assertThat(InterestCalculator.accruedInterest(10_000_000L, BigDecimal.ZERO, 90)).isZero();
    }

    @Test
    @DisplayName("R10: lãi từng ngày xoay quanh 1.643-1.644đ")
    void dailyInterestValues() {
        assertThat(InterestCalculator.dailyInterest(10_000_000L, RATE_6, 1)).isEqualTo(1_643L);
        assertThat(InterestCalculator.dailyInterest(10_000_000L, RATE_6, 2)).isEqualTo(1_644L);
        assertThat(InterestCalculator.dailyInterest(10_000_000L, RATE_6, 7)).isEqualTo(1_643L);
    }

    @ParameterizedTest(name = "gốc={0}, lãi suất={1}, {2} ngày")
    @CsvSource({
            "10000000,  0.045, 30",
            "10000000,  0.06,  90",
            "10000000,  0.07,  180",
            "1000000,   0.06,  90",
            "123456789, 0.07,  180",
            "500000000, 0.07,  180"
    })
    @DisplayName("R10: tổng lãi từng ngày khớp tuyệt đối với lãi tổng")
    void sumOfDailyEqualsTotal(long principal, String rate, int termDays) {
        BigDecimal annualRate = new BigDecimal(rate);

        long sum = 0;
        for (int day = 1; day <= termDays; day++) {
            long daily = InterestCalculator.dailyInterest(principal, annualRate, day);
            assertThat(daily).isGreaterThanOrEqualTo(0L);
            sum += daily;
        }

        assertThat(sum).isEqualTo(InterestCalculator.accruedInterest(principal, annualRate, termDays));
    }

    @Test
    @DisplayName("R23: rút sớm 40 ngày → 5.479đ (thay vì 65.753đ)")
    void earlyWithdrawal() {
        assertThat(InterestCalculator.earlyWithdrawalInterest(10_000_000L, 40)).isEqualTo(5_479L);
    }

    @Test
    @DisplayName("R24: gửi chưa đủ 1 ngày → lãi bằng 0")
    void earlyWithdrawalBeforeFirstDay() {
        assertThat(InterestCalculator.earlyWithdrawalInterest(10_000_000L, 0)).isZero();
    }

    @Test
    @DisplayName("Đầu vào sai bị từ chối")
    void invalidInputs() {
        assertThatThrownBy(() -> InterestCalculator.accruedInterest(0, RATE_6, 90))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterestCalculator.accruedInterest(1_000_000L, new BigDecimal("-0.01"), 90))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterestCalculator.accruedInterest(1_000_000L, RATE_6, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterestCalculator.dailyInterest(1_000_000L, RATE_6, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}