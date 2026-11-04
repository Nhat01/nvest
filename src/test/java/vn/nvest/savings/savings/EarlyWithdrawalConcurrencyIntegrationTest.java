package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.common.InvalidStateTransitionException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;
import vn.nvest.savings.support.ParallelRunner;

class EarlyWithdrawalConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EarlyWithdrawalService earlyWithdrawalService;

    @Autowired
    private MaturityService maturityService;

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private SavingsAccountRepository accountRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    private SavingsAccount openAccount(long userId) {
        return savingsService.open(userId, "SAVE_90", 10_000_000,
                MaturityOption.PAYOUT_ALL, "open:" + UUID.randomUUID());
    }

    private void travelBack(long accountId, int days) {
        jdbc.update("UPDATE savings_accounts SET start_date = start_date - " + days
                + ", maturity_date = maturity_date - " + days + " WHERE id = ?", accountId);
    }

    @Test
    @DisplayName("R52: 4 luồng cùng rút sớm một gói: cùng một biên lai, chỉ trả tiền một lần")
    void concurrentWithdrawalsPayOnce() throws Exception {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openAccount(userId);
        travelBack(account.getId(), 40);

        List<Settlement> results = ParallelRunner.run(4,
                () -> earlyWithdrawalService.withdraw(userId, account.getId()));

        assertThat(results.stream().map(Settlement::getId).distinct().count()).isEqualTo(1);
        assertThat(settlementRepository.countByAccountId(account.getId())).isEqualTo(1);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_005_479);
    }

    @Test
    @DisplayName("R26, R52: rút sớm đua với quyết toán đáo hạn: đúng một bên thắng, tiền không trả hai lần")
    void earlyWithdrawalRacingWithMaturityHasExactlyOneWinner() throws Exception {
        long userId = createUserWithDeposit(100_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                SavingsAccount account = openAccount(userId);
                travelBack(account.getId(), 10);
                LocalDate maturity = accountRepository.findById(account.getId()).orElseThrow().getMaturityDate();
                CountDownLatch go = new CountDownLatch(1);

                Callable<Boolean> early = () -> {
                    go.await();
                    try {
                        earlyWithdrawalService.withdraw(userId, account.getId());
                        return true;
                    } catch (InvalidStateTransitionException e) {
                        return false;   // job đáo hạn đã xong trước
                    }
                };
                Callable<Optional<Settlement>> settle = () -> {
                    go.await();
                    return maturityService.settle(account.getId(), maturity);   // mô phỏng job chạy đúng ngày đáo hạn
                };

                Future<Boolean> earlyFuture = pool.submit(early);
                Future<Optional<Settlement>> settleFuture = pool.submit(settle);
                go.countDown();

                boolean earlyWon = earlyFuture.get(30, TimeUnit.SECONDS);
                boolean settleWon = settleFuture.get(30, TimeUnit.SECONDS).isPresent();

                assertThat(earlyWon).as("vòng %d: đúng một bên thắng", round).isNotEqualTo(settleWon);
                assertThat(settlementRepository.countByAccountId(account.getId())).isEqualTo(1);
                SettlementType expectedType = earlyWon ? SettlementType.EARLY_WITHDRAWAL : SettlementType.MATURITY;
                assertThat(settlementRepository.findByAccountId(account.getId()).orElseThrow().getSettlementType())
                        .isEqualTo(expectedType);
            }
        } finally {
            pool.shutdown();
        }
    }
}