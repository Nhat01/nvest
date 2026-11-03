package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.interest.InterestCalculator;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class InterestAccrualServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private InterestAccrualService accrualService;

    @Autowired
    private InterestAccrualJob accrualJob;

    @Autowired
    private InterestAccrualRepository accrualRepository;

    @Autowired
    private Clock clock;

    /** Mở gói 10.000.000đ, SAVE_90 (6%) cho một user mới. */
    private SavingsAccount openAccount() {
        long userId = createUserWithDeposit(10_000_000);
        return savingsService.open(userId, "SAVE_90", 10_000_000,
                MaturityOption.PAYOUT_ALL, "open:" + UUID.randomUUID());
    }

    @Test
    @DisplayName("Ghi lãi đến hết ngày asOf: 10 ngày, đúng từng ngày và đúng tổng")
    void accruesDaysUpToAsOf() {
        SavingsAccount account = openAccount();
        LocalDate asOf = account.getStartDate().plusDays(9);   // ngày thứ 10

        int created = accrualService.accrueAccount(account.getId(), asOf);

        assertThat(created).isEqualTo(10);
        List<InterestAccrual> rows = accrualRepository.findByAccountIdOrderByDayNumber(account.getId());
        assertThat(rows).hasSize(10);
        assertThat(rows.get(0).getDayNumber()).isEqualTo(1);
        assertThat(rows.get(0).getAccrualDate()).isEqualTo(account.getStartDate());
        assertThat(rows.get(0).getAmount()).isEqualTo(1_643);
        assertThat(rows.get(1).getAmount()).isEqualTo(1_644);
        assertThat(accrualService.getAccruedInterest(account.getId())).isEqualTo(16_438);
        assertThat(accrualService.getAccruedInterest(account.getId()))
                .isEqualTo(InterestCalculator.accruedInterest(10_000_000, account.getAnnualRate(), 10));
    }

    @Test
    @DisplayName("Chạy lại cùng ngày không ghi trùng")
    void rerunIsIdempotent() {
        SavingsAccount account = openAccount();
        LocalDate asOf = account.getStartDate().plusDays(9);

        accrualService.accrueAccount(account.getId(), asOf);
        int second = accrualService.accrueAccount(account.getId(), asOf);

        assertThat(second).isZero();
        assertThat(accrualRepository.countByAccountId(account.getId())).isEqualTo(10);
    }

    @Test
    @DisplayName("R41: lỡ vài ngày thì lần chạy sau ghi bù đủ, khớp công thức")
    void catchUpAfterMissedDays() {
        SavingsAccount account = openAccount();

        int first = accrualService.accrueAccount(account.getId(), account.getStartDate().plusDays(4));
        int catchUp = accrualService.accrueAccount(account.getId(), account.getStartDate().plusDays(9));

        assertThat(first).isEqualTo(5);
        assertThat(catchUp).isEqualTo(5);
        assertThat(accrualRepository.countByAccountId(account.getId())).isEqualTo(10);
        assertThat(accrualService.getAccruedInterest(account.getId())).isEqualTo(16_438);
    }

    @Test
    @DisplayName("R9, R41: dừng đúng ở hết kỳ hạn, tổng lãi 90 ngày = 147.945đ")
    void stopsAtEndOfTerm() {
        SavingsAccount account = openAccount();
        LocalDate wayAfterMaturity = account.getStartDate().plusDays(200);

        int created = accrualService.accrueAccount(account.getId(), wayAfterMaturity);

        assertThat(created).isEqualTo(90);
        List<InterestAccrual> rows = accrualRepository.findByAccountIdOrderByDayNumber(account.getId());
        InterestAccrual last = rows.get(rows.size() - 1);
        assertThat(last.getDayNumber()).isEqualTo(90);
        assertThat(last.getAccrualDate()).isEqualTo(account.getMaturityDate().minusDays(1));
        assertThat(accrualService.getAccruedInterest(account.getId())).isEqualTo(147_945);
        assertThat(accrualService.accrueAccount(account.getId(), wayAfterMaturity)).isZero();
    }

    @Test
    @DisplayName("Chưa tới ngày bắt đầu thì chưa ghi gì")
    void nothingBeforeStartDate() {
        SavingsAccount account = openAccount();

        int created = accrualService.accrueAccount(account.getId(), account.getStartDate().minusDays(1));

        assertThat(created).isZero();
    }

    @Test
    @DisplayName("Gói không còn ACTIVE thì bị bỏ qua")
    void nonActiveAccountIsSkipped() {
        SavingsAccount account = openAccount();
        jdbc.update("UPDATE savings_accounts SET status = 'CLOSED_EARLY' WHERE id = ?", account.getId());

        int created = accrualService.accrueAccount(account.getId(), account.getStartDate().plusDays(9));

        assertThat(created).isZero();
    }

    @Test
    @DisplayName("Job quét và ghi lãi cho gói đang chạy")
    void jobAccruesActiveAccounts() {
        SavingsAccount account = openAccount();

        InterestAccrualJob.RunResult result = accrualJob.runFor(account.getStartDate().plusDays(2));

        assertThat(result.failures()).isZero();
        assertThat(result.accounts()).isGreaterThanOrEqualTo(1);
        assertThat(accrualRepository.countByAccountId(account.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("R42: 4 luồng cùng ghi lãi một gói: chỉ ghi đúng một bộ, không lỗi, không trùng")
    void concurrentRunsDoNotDuplicate() throws Exception {
        SavingsAccount account = openAccount();
        LocalDate asOf = account.getStartDate().plusDays(9);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> task = () -> accrualService.accrueAccount(account.getId(), asOf);
                futures.add(pool.submit(task));
            }
            int totalCreated = 0;
            for (Future<Integer> future : futures) {
                totalCreated += future.get(30, TimeUnit.SECONDS);   // ném lỗi nếu luồng nào thất bại
            }
            assertThat(totalCreated).isEqualTo(10);
        } finally {
            pool.shutdown();
        }
        assertThat(accrualRepository.countByAccountId(account.getId())).isEqualTo(10);
    }
}
