package vn.nvest.savings.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.common.InsufficientFundsException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class WalletConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int THREADS = 8;

    private record Outcome(int succeeded, int insufficientFunds, List<Throwable> otherErrors) {
    }

    /** Chạy task trên nhiều luồng, tất cả được "mở cổng" cùng một lúc để va chạm thật sự. */
    private Outcome runConcurrently(int threads, IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Throwable>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<Throwable> job = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        task.accept(index);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                };
                futures.add(pool.submit(job));
            }
            ready.await();
            go.countDown();

            int succeeded = 0;
            int insufficient = 0;
            List<Throwable> others = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                Throwable error = future.get(60, TimeUnit.SECONDS);
                if (error == null) {
                    succeeded++;
                } else if (error instanceof InsufficientFundsException) {
                    insufficient++;
                } else {
                    others.add(error);
                }
            }
            return new Outcome(succeeded, insufficient, others);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("8 luồng cùng giữ 300đ từ ví 1.000đ: đúng 3 luồng thành công, không bao giờ vượt số dư")
    void concurrentHoldsNeverOverspend() throws Exception {
        long userId = createUserWithDeposit(1_000);

        Outcome outcome = runConcurrently(THREADS, i ->
                walletService.holdForWithdrawal(userId, 300, "hold:" + userId + ":" + i));

        assertThat(outcome.otherErrors()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(3);
        assertThat(outcome.insufficientFunds()).isEqualTo(THREADS - 3);
        assertThat(walletService.getBalance(userId))
                .isEqualTo(new WalletService.WalletBalance(100, 900));
    }

    @Test
    @DisplayName("8 luồng gửi cùng một idempotency key: tiền chỉ cộng đúng một lần")
    void sameKeySentConcurrentlyIsAppliedOnce() throws Exception {
        long userId = createUserWithDeposit(0);
        String key = "webhook:" + UUID.randomUUID();

        Outcome outcome = runConcurrently(THREADS, i -> walletService.deposit(userId, 500, key));

        assertThat(outcome.otherErrors()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(THREADS);   // lần trùng vẫn trả OK
        assertThat(walletService.getBalance(userId).available()).isEqualTo(500);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class, key);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("40 lần nạp song song vào cùng một ví: không mất lần cập nhật nào")
    void noLostUpdates() throws Exception {
        long userId = createUserWithDeposit(0);

        Outcome outcome = runConcurrently(THREADS, i -> {
            for (int j = 0; j < 5; j++) {
                walletService.deposit(userId, 100, "dep:" + userId + ":" + i + ":" + j);
            }
        });

        assertThat(outcome.otherErrors()).isEmpty();
        assertThat(walletService.getBalance(userId).available()).isEqualTo(4_000);
    }
}
