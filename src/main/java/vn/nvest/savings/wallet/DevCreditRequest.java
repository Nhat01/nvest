package vn.nvest.savings.wallet;

import jakarta.validation.constraints.Positive;

public record DevCreditRequest(@Positive long amount) {
}