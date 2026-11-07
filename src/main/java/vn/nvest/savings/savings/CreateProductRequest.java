package vn.nvest.savings.savings;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** annualRatePercent theo phần trăm: 6.25 nghĩa là 6,25%/năm (R67). Kiểm tra lớp đầu của R36. */
public record CreateProductRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9_]{3,30}$",
                message = "code must be 3-30 characters: A-Z, 0-9 or underscore") String code,
        @NotBlank @Size(max = 100) String name,
        @Positive @Max(3650) int termDays,
        @NotNull @DecimalMin("0.00") @DecimalMax("20.00") @Digits(integer = 2, fraction = 2)
        BigDecimal annualRatePercent,
        @Positive long minAmount,
        @Positive long maxAmount) {

    @AssertTrue(message = "maxAmount must not be less than minAmount")
    public boolean isAmountRangeValid() {
        return maxAmount >= minAmount;
    }
}