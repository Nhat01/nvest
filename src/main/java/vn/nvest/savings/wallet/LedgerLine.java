package vn.nvest.savings.wallet;

/** Một dòng cộng/trừ vào một ngăn số dư của một ví. amount dương = cộng, âm = trừ. */
public record LedgerLine(long walletId, BalanceType balanceType, long amount) {
}
