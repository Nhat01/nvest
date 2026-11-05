package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:05:00Z");

    private final PaymentTransactionRepository paymentRepository = mock(PaymentTransactionRepository.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
    private final ReconciliationJob job = new ReconciliationJob(
            paymentRepository, reconciliationService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("R20: một giao dịch lỗi không chặn các giao dịch khác")
    void oneFailureDoesNotStopOthers() {
        when(paymentRepository.findOpenIdsCreatedBefore(eq(PaymentDirection.DEPOSIT), any(), any(Instant.class)))
                .thenReturn(List.of(1L, 2L));
        when(paymentRepository.findOpenIdsCreatedBefore(eq(PaymentDirection.WITHDRAW), any(), any(Instant.class)))
                .thenReturn(List.of(3L, 4L));
        when(reconciliationService.reconcile(1L)).thenReturn(ReconciliationService.Outcome.RESOLVED);
        when(reconciliationService.reconcile(2L)).thenThrow(new IllegalStateException("boom"));
        when(reconciliationService.reconcile(3L)).thenReturn(ReconciliationService.Outcome.STILL_OPEN);
        when(reconciliationService.reconcile(4L)).thenReturn(ReconciliationService.Outcome.ALREADY_SETTLED);

        ReconciliationJob.RunResult result = job.run();

        assertThat(result.examined()).isEqualTo(4);
        assertThat(result.resolved()).isEqualTo(1);
        assertThat(result.stillOpen()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failures()).isEqualTo(1);
        verify(reconciliationService).reconcile(4L);   // giao dịch sau giao dịch lỗi vẫn được xử lý
    }

    @Test
    @DisplayName("R31, R60: ngưỡng thời gian: nạp 30 phút, rút 5 phút")
    void usesTheRightCutoffs() {
        when(paymentRepository.findOpenIdsCreatedBefore(any(), any(), any(Instant.class))).thenReturn(List.of());

        job.run();

        ArgumentCaptor<Instant> depositCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> withdrawalCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).findOpenIdsCreatedBefore(
                eq(PaymentDirection.DEPOSIT), eq(List.of(PaymentStatus.PENDING)), depositCutoff.capture());
        verify(paymentRepository).findOpenIdsCreatedBefore(
                eq(PaymentDirection.WITHDRAW), eq(List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN)),
                withdrawalCutoff.capture());
        assertThat(depositCutoff.getValue()).isEqualTo(Instant.parse("2026-11-09T23:35:00Z"));
        assertThat(withdrawalCutoff.getValue()).isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
    }
}