package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.common.ClockConfig;

class MaturityJobTest {

    // 00:05 ngày 10/11/2026 giờ Việt Nam
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-09T17:05:00Z"), ClockConfig.BUSINESS_ZONE);
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 10);

    private final SavingsAccountRepository accountRepository = mock(SavingsAccountRepository.class);
    private final MaturityService maturityService = mock(MaturityService.class);
    private final MaturityJob job = new MaturityJob(accountRepository, maturityService, CLOCK);

    @Test
    @DisplayName("R20: một gói lỗi không chặn các gói còn lại, và gói lỗi không bị thử lại")
    void oneFailureDoesNotStopOthers() {
        when(accountRepository.findIdsByStatusMaturingOnOrBefore(AccountStatus.ACTIVE, AS_OF))
                .thenReturn(List.of(1L, 2L, 3L));
        when(maturityService.settle(1L, AS_OF)).thenReturn(Optional.of(mock(Settlement.class)));
        when(maturityService.settle(2L, AS_OF)).thenThrow(new IllegalStateException("boom"));
        when(maturityService.settle(3L, AS_OF)).thenReturn(Optional.empty());

        MaturityJob.RunResult result = job.runFor(AS_OF);

        assertThat(result.due()).isEqualTo(3);
        assertThat(result.settled()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failures()).isEqualTo(1);
        verify(maturityService, times(1)).settle(2L, AS_OF);   // chỉ thử một lần dù lượt sau vẫn thấy nó
    }

    @Test
    @DisplayName("R49: gói tái tục mà bản thân đã đến hạn được xử lý ở lượt sau")
    void renewedAccountThatIsAlsoDueIsPickedUpInNextPass() {
        when(accountRepository.findIdsByStatusMaturingOnOrBefore(AccountStatus.ACTIVE, AS_OF))
                .thenReturn(List.of(1L))          // lượt 1: chỉ gói 1
                .thenReturn(List.of(1L, 2L));     // lượt 2: gói 2 mới sinh ra cũng đã đến hạn
        when(maturityService.settle(1L, AS_OF)).thenReturn(Optional.of(mock(Settlement.class)));
        when(maturityService.settle(2L, AS_OF)).thenReturn(Optional.of(mock(Settlement.class)));

        MaturityJob.RunResult result = job.runFor(AS_OF);

        assertThat(result.due()).isEqualTo(2);
        assertThat(result.settled()).isEqualTo(2);
        verify(maturityService, times(1)).settle(1L, AS_OF);
        verify(maturityService, times(1)).settle(2L, AS_OF);
    }

    @Test
    @DisplayName("R19: job theo lịch dùng ngày HÔM NAY (gói đáo hạn hôm nay thì xử lý hôm nay)")
    void scheduledRunUsesToday() {
        when(accountRepository.findIdsByStatusMaturingOnOrBefore(AccountStatus.ACTIVE, AS_OF))
                .thenReturn(List.of());

        job.runScheduled();

        verify(accountRepository, times(1)).findIdsByStatusMaturingOnOrBefore(AccountStatus.ACTIVE, AS_OF);
    }
}