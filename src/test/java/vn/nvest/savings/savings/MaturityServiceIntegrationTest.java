package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;
import vn.nvest.savings.wallet.WalletService;

class MaturityServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String CUSTODY = "PLATFORM_SAVINGS_CUSTODY";
    private static final String POOL = "PLATFORM_INTEREST_POOL";

    @Autowired
    private MaturityService maturityService;

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private SavingsAccountRepository accountRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private InterestAccrualRepository accrualRepository;

    private long systemBalance(String walletType) {
        Long balance = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE wallet_type = ?", Long.class, walletType);
        return balance;
    }

    private SavingsAccount open(long userId, String productCode, long amount, MaturityOption option) {
        return savingsService.open(userId, productCode, amount, option, "open:" + UUID.randomUUID());
    }

    private SavingsAccount reload(long accountId) {
        return accountRepository.findById(accountId).orElseThrow();
    }

    @Test
    @DisplayName("PAYOUT_ALL: user nhận gốc + lãi 10.147.945đ, custody về như cũ, gói CLOSED")
    void payoutAll() {
        long userId = createUserWithDeposit(10_000_000);
        long custodyBefore = systemBalance(CUSTODY);
        long poolBefore = systemBalance(POOL);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.PAYOUT_ALL);

        Settlement settlement = maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();

        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(10_147_945, 0));
        assertThat(systemBalance(CUSTODY)).isEqualTo(custodyBefore);
        assertThat(systemBalance(POOL)).isEqualTo(poolBefore - 147_945);
        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(settlement.getPrincipal()).isEqualTo(10_000_000);
        assertThat(settlement.getInterest()).isEqualTo(147_945);
        assertThat(settlement.getOptionApplied()).isEqualTo(MaturityOption.PAYOUT_ALL);
        assertThat(settlement.getFallbackReason()).isNull();
        assertThat(settlement.getNewAccountId()).isNull();
        assertThat(settlement.getLedgerTransactionId()).isNotNull();
        // Bảng lãi hàng ngày đã đủ 90 dòng và khớp công thức (R43)
        assertThat(accrualRepository.countByAccountId(account.getId())).isEqualTo(90);
        assertThat(accrualRepository.sumAmountByAccountId(account.getId())).isEqualTo(147_945);
    }

    @Test
    @DisplayName("RENEW_PRINCIPAL: lãi về ví, gốc ở lại custody và mở gói kỳ 2")
    void renewPrincipal() {
        long userId = createUserWithDeposit(10_000_000);
        long custodyBefore = systemBalance(CUSTODY);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.RENEW_PRINCIPAL);

        Settlement settlement = maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();
        SavingsAccount next = reload(settlement.getNewAccountId());

        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(147_945, 0));
        assertThat(systemBalance(CUSTODY)).isEqualTo(custodyBefore + 10_000_000);
        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.RENEWED);
        assertThat(next.getParentId()).isEqualTo(account.getId());
        assertThat(next.getCycleNo()).isEqualTo(2);
        assertThat(next.getPrincipal()).isEqualTo(10_000_000);
        assertThat(next.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(next.getMaturityOption()).isEqualTo(MaturityOption.RENEW_PRINCIPAL);
    }

    @Test
    @DisplayName("RENEW_ALL: lãi nhập gốc, kỳ 2 lãi 150.133đ, gốc kỳ 3 là 10.298.078đ")
    void renewAllCompoundsAcrossCycles() {
        long userId = createUserWithDeposit(10_000_000);
        long custodyBefore = systemBalance(CUSTODY);
        long poolBefore = systemBalance(POOL);
        SavingsAccount first = open(userId, "SAVE_90", 10_000_000, MaturityOption.RENEW_ALL);

        Settlement s1 = maturityService.settle(first.getId(), first.getMaturityDate()).orElseThrow();
        SavingsAccount second = reload(s1.getNewAccountId());

        assertThat(second.getPrincipal()).isEqualTo(10_147_945);
        assertThat(walletService.getBalance(userId).available()).isZero();
        assertThat(systemBalance(CUSTODY)).isEqualTo(custodyBefore + 10_147_945);
        assertThat(systemBalance(POOL)).isEqualTo(poolBefore - 147_945);

        Settlement s2 = maturityService.settle(second.getId(), second.getMaturityDate()).orElseThrow();
        SavingsAccount third = reload(s2.getNewAccountId());

        assertThat(s2.getPrincipal()).isEqualTo(10_147_945);
        assertThat(s2.getInterest()).isEqualTo(150_133);
        assertThat(third.getPrincipal()).isEqualTo(10_298_078);
        assertThat(third.getCycleNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("R18: sản phẩm ngừng bán thì RENEW_ALL tự chuyển sang trả hết về ví")
    void fallbackWhenProductStopped() {
        long userId = createUserWithDeposit(5_000_000);
        SavingsAccount account = open(userId, "FLEX_30", 5_000_000, MaturityOption.RENEW_ALL);
        try {
            jdbc.update("UPDATE savings_products SET status = 'STOPPED' WHERE code = 'FLEX_30'");

            Settlement settlement = maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();

            assertThat(settlement.getOptionApplied()).isEqualTo(MaturityOption.PAYOUT_ALL);
            assertThat(settlement.getFallbackReason()).isEqualTo("PRODUCT_NOT_ON_SALE");
            assertThat(settlement.getNewAccountId()).isNull();
            assertThat(walletService.getBalance(userId).available()).isEqualTo(5_018_493);
            assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.CLOSED);
        } finally {
            jdbc.update("UPDATE savings_products SET status = 'ON_SALE' WHERE code = 'FLEX_30'");
        }
    }

    @Test
    @DisplayName("R18: gốc + lãi vượt mức tối đa 500 triệu thì trả hết về ví")
    void fallbackWhenRenewedAmountExceedsMax() {
        long userId = createUserWithDeposit(500_000_000);
        SavingsAccount account = open(userId, "SAVE_90", 500_000_000, MaturityOption.RENEW_ALL);

        Settlement settlement = maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();

        assertThat(settlement.getOptionApplied()).isEqualTo(MaturityOption.PAYOUT_ALL);
        assertThat(settlement.getFallbackReason()).isEqualTo("AMOUNT_OUT_OF_RANGE");
        assertThat(walletService.getBalance(userId).available()).isEqualTo(507_397_260);
    }

    @Test
    @DisplayName("R5, R17: trả lãi theo lãi suất đã chốt, nhưng gói tái tục dùng lãi suất hiện hành")
    void renewalUsesCurrentRateWhilePayingLockedRate() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.RENEW_PRINCIPAL);
        try {
            jdbc.update("UPDATE savings_products SET annual_rate = 0.0550 WHERE code = 'SAVE_90'");

            Settlement settlement = maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();
            SavingsAccount next = reload(settlement.getNewAccountId());

            assertThat(settlement.getInterest()).isEqualTo(147_945);   // theo 6% đã chốt
            assertThat(next.getAnnualRate()).isEqualByComparingTo("0.055");   // theo 5,5% hiện hành
            assertThat(reload(account.getId()).getAnnualRate()).isEqualByComparingTo("0.06");
        } finally {
            jdbc.update("UPDATE savings_products SET annual_rate = 0.0600 WHERE code = 'SAVE_90'");
        }
    }

    @Test
    @DisplayName("R19, R44: quyết toán trễ 5 ngày không trả thêm lãi, gói mới vẫn bắt đầu đúng ngày đáo hạn cũ")
    void lateSettlementDoesNotChangeInterestOrStartDate() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.RENEW_PRINCIPAL);

        Settlement settlement = maturityService
                .settle(account.getId(), account.getMaturityDate().plusDays(5)).orElseThrow();
        SavingsAccount next = reload(settlement.getNewAccountId());

        assertThat(settlement.getInterest()).isEqualTo(147_945);
        assertThat(accrualRepository.countByAccountId(account.getId())).isEqualTo(90);
        assertThat(next.getStartDate()).isEqualTo(account.getMaturityDate());
    }

    @Test
    @DisplayName("R47: chưa đến hạn thì không quyết toán")
    void notDueYetIsSkipped() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.PAYOUT_ALL);

        var result = maturityService.settle(account.getId(), account.getMaturityDate().minusDays(1));

        assertThat(result).isEmpty();
        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(settlementRepository.countByAccountId(account.getId())).isZero();
        assertThat(walletService.getBalance(userId).available()).isZero();
    }

    @Test
    @DisplayName("R47: quyết toán hai lần chỉ trả tiền một lần")
    void settlingTwiceIsIdempotent() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.PAYOUT_ALL);

        var first = maturityService.settle(account.getId(), account.getMaturityDate());
        var second = maturityService.settle(account.getId(), account.getMaturityDate());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(settlementRepository.countByAccountId(account.getId())).isEqualTo(1);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_147_945);
    }

    @Test
    @DisplayName("R43: lãi hàng ngày lệch công thức thì hủy sạch quyết toán")
    void accrualMismatchAbortsSettlementCompletely() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = open(userId, "SAVE_90", 10_000_000, MaturityOption.PAYOUT_ALL);
        // Cố tình ghi sai lãi ngày 1 (đúng ra là 1.643đ)
        jdbc.update("INSERT INTO interest_accruals (account_id, day_number, accrual_date, amount) VALUES (?, 1, ?, 1)",
                account.getId(), java.sql.Date.valueOf(account.getStartDate()));

        assertThatThrownBy(() -> maturityService.settle(account.getId(), account.getMaturityDate()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(settlementRepository.countByAccountId(account.getId())).isZero();
        assertThat(walletService.getBalance(userId).available()).isZero();   // chưa trả đồng nào
    }

    @Test
    @DisplayName("R46: lãi suất 0% và tái tục thì không ghi sổ cái nhưng vẫn tạo gói mới")
    void zeroInterestRenewalMovesNoMoney() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account;
        try {
            jdbc.update("UPDATE savings_products SET annual_rate = 0 WHERE code = 'SAVE_90'");
            account = open(userId, "SAVE_90", 10_000_000, MaturityOption.RENEW_PRINCIPAL);
        } finally {
            jdbc.update("UPDATE savings_products SET annual_rate = 0.0600 WHERE code = 'SAVE_90'");
        }
        long custodyBefore = systemBalance(CUSTODY);

        Settlement settlement = maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();

        assertThat(settlement.getInterest()).isZero();
        assertThat(settlement.getLedgerTransactionId()).isNull();
        assertThat(settlement.getNewAccountId()).isNotNull();
        assertThat(systemBalance(CUSTODY)).isEqualTo(custodyBefore);
        assertThat(walletService.getBalance(userId).available()).isZero();
        assertThat(reload(settlement.getNewAccountId()).getAnnualRate()).isEqualByComparingTo("0.06");
    }
}