package vn.nvest.savings.savings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.common.ClockConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InterestAccrualJobTest {

    // 00:05 ngày 10/11/2026 giờ Việt Nam
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-09T17:05:00Z"), ClockConfig.BUSINESS_ZONE);

    private final SavingsAccountRepository accountRepository = mock(SavingsAccountRepository.class);
    private final InterestAccrualService accrualService = mock(InterestAccrualService.class);
    private final InterestAccrualJob job = new InterestAccrualJob(accountRepository, accrualService, CLOCK);

    @Test
    @DisplayName("R20: một gói lỗi không chặn các gói còn lại")
    void oneFailureDoesNotStopOthers() {
        LocalDate asOf = LocalDate.of(2026, 11, 9);
        when(accountRepository.findIdsByStatusStartedOnOrBefore(AccountStatus.ACTIVE, asOf))
                .thenReturn(List.of(1L, 2L, 3L));
        when(accrualService.accrueAccount(1L, asOf)).thenReturn(3);
        when(accrualService.accrueAccount(2L, asOf)).thenThrow(new IllegalStateException("boom"));
        when(accrualService.accrueAccount(3L, asOf)).thenReturn(5);

        InterestAccrualJob.RunResult result = job.runFor(asOf);

        assertThat(result.accounts()).isEqualTo(3);
        assertThat(result.daysCreated()).isEqualTo(8);
        assertThat(result.failures()).isEqualTo(1);
        verify(accrualService).accrueAccount(3L, asOf);   // gói sau gói lỗi vẫn được xử lý
    }

    @Test
    @DisplayName("R40: job theo lịch ghi lãi đến hết HÔM QUA")
    void scheduledRunUsesYesterday() {
        LocalDate yesterday = LocalDate.of(2026, 11, 9);
        when(accountRepository.findIdsByStatusStartedOnOrBefore(AccountStatus.ACTIVE, yesterday))
                .thenReturn(List.of());

        job.runScheduled();

        verify(accountRepository).findIdsByStatusStartedOnOrBefore(AccountStatus.ACTIVE, yesterday);
    }
}