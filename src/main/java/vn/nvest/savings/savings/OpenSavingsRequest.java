package vn.nvest.savings.savings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OpenSavingsRequest(@NotBlank String productCode, @Positive long amount,
                                 @NotNull MaturityOption maturityOption) {
}