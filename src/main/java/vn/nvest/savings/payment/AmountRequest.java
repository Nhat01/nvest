package vn.nvest.savings.payment;

import jakarta.validation.constraints.Positive;

/** Body của nạp và rút. Hạn mức cụ thể (R27, R57) do service kiểm tra. */
public record AmountRequest(@Positive long amount) {
}