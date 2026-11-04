package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

class MaturityConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MaturityService maturityService;

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private SettlementRepository settlementRepository;

    private SavingsAccount openPayoutAll(long userId) {
        return savingsService.open(userId, "SAVE_90", 10_000_000,
                MaturityOption.PAYOUT_ALL, "open:" + UUID.randomUUID());
    }

    /** Chạy cùng một task trên nhiều luồng, mở cổng đồng thời; lỗi của luồng nào sẽ làm test đỏ. */
    private <T> List<T> runInParallel(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("R47: 4 luồng cùng quyết toán một gói: chỉ một luồng trả tiền")
    void concurrentSettlementPaysExactlyOnce() throws Exception {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = openPayoutAll(userId);

        List<Optional<Settlement>> results = runInParallel(4,
                () -> maturityService.settle(account.getId(), account.getMaturityDate()));

        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(settlementRepository.countByAccountId(account.getId())).isEqualTo(1);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_147_945);
    }

    @Test
    @DisplayName("R26, R48: đổi tùy chọn và quyết toán cùng lúc: tùy chọn không bao giờ bị mất")
    void changingOptionRacingWithSettlementNeverLosesTheChange() throws Exception {
        long userId = createUserWithDeposit(100_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                SavingsAccount account = openPayoutAll(userId);
                CountDownLatch go = new CountDownLatch(1);

                Callable<Boolean> change = () -> {
                    go.await();
                    try {
                        savingsService.changeMaturityOption(userId, account.getId(), MaturityOption.RENEW_ALL);
                        return true;
                    } catch (InvalidStateTransitionException e) {
                        return false;   // quyết toán đã xong trước, gói không còn ACTIVE
                    }
                };
                Callable<Optional<Settlement>> settle = () -> {
                    go.await();
                    return maturityService.settle(account.getId(), account.getMaturityDate());
                };

                Future<Boolean> changeFuture = pool.submit(change);
                Future<Optional<Settlement>> settleFuture = pool.submit(settle);
                go.countDown();

                boolean changeSucceeded = changeFuture.get(30, TimeUnit.SECONDS);
                Settlement settlement = settleFuture.get(30, TimeUnit.SECONDS).orElseThrow();

                // Đổi thành công thì quyết toán PHẢI dùng tùy chọn mới; ngược lại phải dùng tùy chọn cũ
                MaturityOption expected = changeSucceeded ? MaturityOption.RENEW_ALL : MaturityOption.PAYOUT_ALL;
                assertThat(settlement.getOptionApplied()).as("vòng %d", round).isEqualTo(expected);
            }
        } finally {
            pool.shutdown();
        }
    }
}