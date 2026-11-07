package vn.nvest.savings.savings;

import jakarta.validation.constraints.NotNull;

public record ChangeOptionRequest(@NotNull MaturityOption maturityOption) {
}