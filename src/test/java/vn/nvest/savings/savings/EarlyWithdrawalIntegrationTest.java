package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.common.AccountNotFoundException;
import vn.nvest.savings.common.InvalidStateTransitionException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class EarlyWithdrawalIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String CUSTODY = "PLATFORM_SAVINGS_CUSTODY";
    private static final String POOL = "PLATFORM_INTEREST_POOL";

    @Autowired
    private EarlyWithdrawalService earlyWithdrawalService;

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private MaturityService maturityService;

    @Autowired
    private InterestAccrualService accrualService;

    @Autowired
    private InterestAccrualRepository accrualRepository;

    @Autowired
    private SavingsAccountRepository accountRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private Clock clock;

    private long systemBalance(String walletType) {
        Long balance = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE wallet_type = ?", Long.class, walletType);
        return balance;
    }

    private SavingsAccount openAccount(long userId) {
        return savingsService.open(userId, "SAVE_90", 10_000_000,
                MaturityOption.PAYOUT_ALL, "open:" + UUID.randomUUID());
    }

    /** Dời cả ngày bắt đầu và đáo hạn lùi `days` ngày, như thể gói đã mở từ trước (R9 vẫn thỏa). */
    private void travelBack(long accountId, int days) {
        jdbc.update("UPDATE savings_accounts SET start_date = start_date - " + days
                + ", maturity_date = maturity_date - " + days + " WHERE id = ?", accountId);
    }

    private SavingsAccount reload(long accountId) {
        return accountRepository.findById(accountId).orElseThrow();
    }

    @Test
    @DisplayName("R23: rút sau 40 ngày hoàn đủ gốc, lãi chỉ 5.479đ dù lãi tạm tính đã ghi 65.753đ (R25)")
    void withdrawAfterFortyDays() {
        long userId = createUserWithDeposit(10_000_000);
        long custodyBefore = systemBalance(CUSTODY);
        long poolBefore = systemBalance(POOL);
        SavingsAccount account = openAccount(userId);
        travelBack(account.getId(), 40);
        // Job lãi đã ghi 40 ngày theo lãi suất 6%
        accrualService.accrueAccount(account.getId(), LocalDate.now(clock).minusDays(1));
        assertThat(accrualRepository.sumAmountByAccountId(account.getId())).isEqualTo(65_753);

        Settlement settlement = earlyWithdrawalService.withdraw(userId, account.getId());

        assertThat(settlement.getSettlementType()).isEqualTo(SettlementType.EARLY_WITHDRAWAL);
        assertThat(settlement.getOptionApplied()).isEqualTo(MaturityOption.PAYOUT_ALL);
        assertThat(settlement.getPrincipal()).isEqualTo(10_000_000);
        assertThat(settlement.getInterest()).isEqualTo(5_479);
        assertThat(settlement.getNewAccountId()).isNull();
        assertThat(settlement.getLedgerTransactionId()).isNotNull();
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_005_479);
        assertThat(systemBalance(CUSTODY)).isEqualTo(custodyBefore);
        assertThat(systemBalance(POOL)).isEqualTo(poolBefore - 5_479);
        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.CLOSED_EARLY);
        // R25, R53: các dòng lãi hàng ngày còn nguyên làm lịch sử
        assertThat(accrualRepository.countByAccountId(account.getId())).isEqualTo(40);
    }

    @Test
    @DisplayName("R24: rút ngay trong ngày mở gói thì lãi bằng 0, vẫn hoàn đủ gốc")
    void withdrawOnTheSameDayPaysNoInterest() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openAccount(userId);

        Settlement settlement = earlyWithdrawalService.withdraw(userId, account.getId());

        assertThat(settlement.getInterest()).isZero();
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_000_000);
        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.CLOSED_EARLY);
    }

    @Test
    @DisplayName("Sau khi rút sớm, job lãi và job đáo hạn không động tới gói nữa")
    void jobsIgnoreAnEarlyWithdrawnAccount() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openAccount(userId);
        travelBack(account.getId(), 40);
        earlyWithdrawalService.withdraw(userId, account.getId());
        SavingsAccount reloaded = reload(account.getId());

        assertThat(accrualService.accrueAccount(account.getId(), LocalDate.now(clock))).isZero();
        assertThat(maturityService.settle(account.getId(), reloaded.getMaturityDate())).isEmpty();
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_005_479);
    }

    @Test
    @DisplayName("R52: gửi lại yêu cầu rút sớm thì trả biên lai cũ và chỉ trả tiền một lần")
    void withdrawingTwiceReturnsTheSameSettlement() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openAccount(userId);
        travelBack(account.getId(), 40);

        Settlement first = earlyWithdrawalService.withdraw(userId, account.getId());
        Settlement second = earlyWithdrawalService.withdraw(userId, account.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(settlementRepository.countByAccountId(account.getId())).isEqualTo(1);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_005_479);
    }

    @Test
    @DisplayName("Người khác không rút được gói của bạn")
    void strangerCannotWithdraw() {
        long owner = createUserWithDeposit(10_000_000);
        long stranger = createUserWithDeposit(0);
        SavingsAccount account = openAccount(owner);

        assertThatThrownBy(() -> earlyWithdrawalService.withdraw(stranger, account.getId()))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(walletService.getBalance(stranger).available()).isZero();
    }

    @Test
    @DisplayName("R51: đã đến ngày đáo hạn thì không rút sớm được nữa")
    void cannotWithdrawEarlyOnceMaturityReached() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openAccount(userId);
        travelBack(account.getId(), 100);   // hôm nay đã quá ngày đáo hạn

        assertThatThrownBy(() -> earlyWithdrawalService.withdraw(userId, account.getId()))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(reload(account.getId()).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(walletService.getBalance(userId).available()).isZero();
    }

    @Test
    @DisplayName("R51: gói đã đáo hạn xong (CLOSED) thì không rút sớm được")
    void cannotWithdrawAfterMaturitySettlement() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openAccount(userId);
        maturityService.settle(account.getId(), account.getMaturityDate());

        assertThatThrownBy(() -> earlyWithdrawalService.withdraw(userId, account.getId()))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_147_945);   // chỉ một lần trả
    }
}