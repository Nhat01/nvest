package vn.nvest.savings.wallet;

import java.time.Instant;

/** Một dòng sao kê: một biến động của một ngăn số dư. amount dương là cộng, âm là trừ. */
public record StatementLine(Long entryId, LedgerTxType txType, BalanceType balanceType, long amount,
                            String referenceType, Long referenceId, String description, Instant createdAt) {
}