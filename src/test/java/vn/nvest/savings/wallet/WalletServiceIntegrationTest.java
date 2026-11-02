package vn.nvest.savings.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.InsufficientFundsException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Test
    @DisplayName("Nạp tiền tăng số dư và ghi sổ cái có tổng bằng 0")
    void depositIncreasesBalanceAndLedgerBalances() {
        long userId = createUserWithDeposit(0);

        LedgerTransaction tx = walletService.deposit(userId, 10_000_000, "deposit:" + UUID.randomUUID());

        assertThat(walletService.getBalance(userId))
                .isEqualTo(new WalletService.WalletBalance(10_000_000, 0));
        List<LedgerEntry> entries = entryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().mapToLong(LedgerEntry::getAmount).sum()).isZero();
    }

    @Test
    @DisplayName("R35: gửi lại cùng key thì chỉ ghi một lần")
    void depositWithSameKeyIsAppliedOnce() {
        long userId = createUserWithDeposit(0);
        String key = "deposit:" + UUID.randomUUID();

        LedgerTransaction first = walletService.deposit(userId, 500, key);
        LedgerTransaction second = walletService.deposit(userId, 500, key);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(walletService.getBalance(userId).available()).isEqualTo(500);
    }

    @Test
    @DisplayName("Dùng lại key cho loại giao dịch khác bị từ chối")
    void reusingKeyForDifferentTypeIsRejected() {
        long userId = createUserWithDeposit(0);
        String key = "shared:" + UUID.randomUUID();
        walletService.deposit(userId, 1_000, key);

        assertThatThrownBy(() -> walletService.holdForWithdrawal(userId, 100, key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("R29: hold chuyển tiền từ AVAILABLE sang HELD")
    void holdMovesMoneyToHeld() {
        long userId = createUserWithDeposit(1_000);

        walletService.holdForWithdrawal(userId, 400, "hold:" + userId);

        assertThat(walletService.getBalance(userId))
                .isEqualTo(new WalletService.WalletBalance(600, 400));
    }

    @Test
    @DisplayName("Hold quá số dư bị từ chối và hủy sạch, không để lại dấu vết nào")
    void holdBeyondBalanceIsRolledBackCompletely() {
        long userId = createUserWithDeposit(1_000);
        String key = "hold-too-much:" + userId;

        assertThatThrownBy(() -> walletService.holdForWithdrawal(userId, 1_001, key))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletService.getBalance(userId))
                .isEqualTo(new WalletService.WalletBalance(1_000, 0));
        assertThat(transactionRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    @DisplayName("Vòng đời rút tiền: release trả tiền về, confirm đưa tiền ra ngoài")
    void withdrawalLifecycle() {
        long userId = createUserWithDeposit(1_000);

        walletService.holdForWithdrawal(userId, 400, "h1:" + userId);
        walletService.releaseHold(userId, 400, "r1:" + userId);
        assertThat(walletService.getBalance(userId))
                .isEqualTo(new WalletService.WalletBalance(1_000, 0));

        walletService.holdForWithdrawal(userId, 300, "h2:" + userId);
        walletService.confirmWithdrawal(userId, 300, "c2:" + userId);
        assertThat(walletService.getBalance(userId))
                .isEqualTo(new WalletService.WalletBalance(700, 0));
    }

    @Test
    @DisplayName("Không thể confirm nhiều hơn số tiền đang HELD")
    void cannotConfirmMoreThanHeld() {
        long userId = createUserWithDeposit(1_000);

        assertThatThrownBy(() -> walletService.confirmWithdrawal(userId, 1, "c-none:" + userId))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("Đối soát: tổng ledger của mọi ví khớp số dư, và toàn hệ thống cộng lại bằng 0")
    void ledgerMatchesWalletBalances() {
        long userId = createUserWithDeposit(5_000);
        walletService.holdForWithdrawal(userId, 2_000, "recon-h:" + userId);
        walletService.confirmWithdrawal(userId, 500, "recon-c:" + userId);

        for (Wallet wallet : walletRepository.findAll()) {
            for (BalanceType type : BalanceType.values()) {
                assertThat(entryRepository.sumByWalletAndBalanceType(wallet.getId(), type))
                        .as("wallet %s, %s", wallet.getId(), type)
                        .isEqualTo(wallet.balanceOf(type));
            }
        }
        Long ledgerTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", Long.class);
        Long walletTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(available_balance + held_balance), 0) FROM wallets", Long.class);
        assertThat(ledgerTotal).isZero();
        assertThat(walletTotal).isZero();
    }

    @Test
    @DisplayName("R32: database từ chối sửa/xóa sổ cái dù đi đường SQL trực tiếp")
    void ledgerCannotBeModifiedEvenWithRawSql() {
        long userId = createUserWithDeposit(1_000);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount = 1 WHERE wallet_id = (SELECT id FROM wallets WHERE user_id = ?)",
                userId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("Database từ chối ví user âm dù đi đường SQL trực tiếp")
    void databaseRejectsNegativeUserWallet() {
        long userId = createUserWithDeposit(1_000);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE wallets SET available_balance = -1 WHERE user_id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
