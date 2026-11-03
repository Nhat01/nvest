package vn.nvest.savings.savings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SavingsServiceConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private SavingsService savingsService;

    /** Kết quả của một luồng: hoặc id gói đã mở, hoặc lỗi đã ném ra. */
    private record Result(Long accountId, Throwable error) {
    }

    @Test
    @DisplayName("R38: 8 luồng gửi cùng một key: tất cả nhận cùng một gói, chỉ trừ tiền một lần")
    void sameKeySentConcurrentlyOpensExactlyOneAccount() throws Exception {
        long userId = createUserWithDeposit(10_000_000);
        String key = "open:" + UUID.randomUUID();

        List<Result> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Result>> futures = new ArrayList<>();

            for (int i = 0; i < THREADS; i++) {
                Callable<Result> job = () -> {
                    ready.countDown();   // báo đã tới vạch xuất phát
                    go.await();          // đứng chờ tới khi cả 8 luồng cùng sẵn sàng
                    try {
                        SavingsAccount account = savingsService.open(
                                userId, "SAVE_90", 3_000_000, MaturityOption.PAYOUT_ALL, key);
                        return new Result(account.getId(), null);
                    } catch (Throwable t) {
                        return new Result(null, t);
                    }
                };
                futures.add(pool.submit(job));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("tất cả luồng phải tới vạch").isTrue();
            go.countDown();   // thả cùng lúc

            for (Future<Result> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
        }

        List<Throwable> errors = results.stream()
                .map(Result::error)
                .filter(e -> e != null)
                .toList();
        Set<Long> accountIds = results.stream()
                .map(Result::accountId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        // Lần gửi trùng phải trả gói cũ chứ không được ném lỗi
        assertThat(errors).as("không luồng nào được ném lỗi").isEmpty();
        assertThat(accountIds).as("mọi luồng nhận cùng một gói").hasSize(1);

        // Chỉ một gói và một lần trừ tiền
        assertThat(savingsService.listByUser(userId)).hasSize(1);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(7_000_000);
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?",
                Integer.class, "open-savings:" + key);
        assertThat(ledgerRows).isEqualTo(1);
    }
}