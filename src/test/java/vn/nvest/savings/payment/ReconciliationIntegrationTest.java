package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;
import vn.nvest.savings.wallet.WalletService;

class ReconciliationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReconciliationJob reconciliationJob;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private WithdrawalRecorder withdrawalRecorder;

    @Autowired
    private MockPaymentGateway gateway;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    /** Làm "già" giao dịch đi một số phút, như thể nó đã tạo từ lâu. */
    private void age(long paymentId, int minutes) {
        jdbc.update("UPDATE payment_transactions SET created_at = now() - interval '" + minutes
                + " minutes' WHERE id = ?", paymentId);
    }

    private PaymentTransaction reload(long paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private PaymentTransaction newDeposit(long userId, long amount) {
        return depositService.createDeposit(userId, amount, "dep:" + UUID.randomUUID()).payment();
    }

    private long ledgerRows(String idempotencyKey) {
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Long.class, idempotencyKey);
        return rows;
    }

    // ---------- Lệnh nạp (R31) ----------

    @Test
    @DisplayName("R31: nạp PENDING quá 30 phút, webhook bị lỡ nhưng khách đã trả: đối soát cộng tiền")
    void lostWebhookIsRecoveredByReconciliation() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        gateway.markPaidByCustomer(payment.getOrderCode());
        age(payment.getId(), 31);

        ReconciliationJob.RunResult result = reconciliationJob.run();

        assertThat(result.resolved()).isGreaterThanOrEqualTo(1);
        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("R31: nạp PENDING chưa quá 30 phút thì chưa hỏi cổng")
    void youngPendingDepositIsNotExamined() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        gateway.markPaidByCustomer(payment.getOrderCode());
        age(payment.getId(), 10);

        reconciliationJob.run();

        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(walletService.getBalance(userId).available()).isZero();
    }

    @Test
    @DisplayName("R31: nạp quá 30 phút mà cổng báo thất bại hoặc không có dấu vết thì FAILED; cổng còn chờ thì giữ PENDING")
    void oldPendingDepositFailsOrStaysDependingOnGateway() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction failedAtGateway = newDeposit(userId, 100_000);
        PaymentTransaction unknownToGateway = newDeposit(userId, 200_000);
        PaymentTransaction stillWaiting = newDeposit(userId, 300_000);
        gateway.markFailedByCustomer(failedAtGateway.getOrderCode());
        // unknownToGateway: cổng không có dấu vết -> bỏ khỏi bộ nhớ cổng giả
        gateway.forget(unknownToGateway.getOrderCode());
        // stillWaiting: cổng vẫn báo PENDING
        for (PaymentTransaction p : new PaymentTransaction[] {failedAtGateway, unknownToGateway, stillWaiting}) {
            age(p.getId(), 31);
        }

        reconciliationJob.run();

        assertThat(reload(failedAtGateway.getId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reload(unknownToGateway.getId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reload(stillWaiting.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(walletService.getBalance(userId).available()).isZero();
    }

    // ---------- Lệnh rút (R30, R60) ----------

    @Test
    @DisplayName("R30: timeout nhưng cổng đã chi: đối soát xác nhận, tiền ra hẳn và KHÔNG trả lại")
    void unknownWithdrawalThatWasPaidIsConfirmed() {
        long userId = createUserWithDeposit(1_000_000);
        gateway.scriptNextPayout(MockPaymentGateway.PayoutBehavior.TIMEOUT_BUT_EXECUTED);
        PaymentTransaction payment = withdrawalService.withdraw(userId, 300_000, "wd:" + UUID.randomUUID());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        age(payment.getId(), 6);

        reconciliationJob.run();

        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 0));
        assertThat(ledgerRows("withdraw-confirm:" + payment.getOrderCode())).isEqualTo(1);
        assertThat(ledgerRows("withdraw-release:" + payment.getOrderCode())).isZero();
    }

    @Test
    @DisplayName("R60: cổng không có dấu vết thì chỉ trả tiền sau 2 giờ; trước đó giữ UNKNOWN và giữ tiền")
    void notFoundWithdrawalIsReleasedOnlyAfterTheGracePeriod() {
        long userId = createUserWithDeposit(1_000_000);
        gateway.scriptNextPayout(MockPaymentGateway.PayoutBehavior.TIMEOUT_NOT_EXECUTED);
        PaymentTransaction payment = withdrawalService.withdraw(userId, 300_000, "wd:" + UUID.randomUUID());
        age(payment.getId(), 30);   // đã quá 5 phút nhưng chưa tới 2 giờ

        reconciliationJob.run();

        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 300_000));

        age(payment.getId(), 180);   // quá 2 giờ
        reconciliationJob.run();

        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(1_000_000, 0));
        assertThat(ledgerRows("withdraw-release:" + payment.getOrderCode())).isEqualTo(1);
    }

    @Test
    @DisplayName("R58: bước 2 bị lỡ (cổng đã chi nhưng DB chưa kịp ghi): lệnh PENDING được đối soát hoàn tất")
    void withdrawalLeftPendingAfterGatewayPaidIsCompleted() {
        long userId = createUserWithDeposit(1_000_000);
        // Mô phỏng: transaction 1 đã commit, cổng đã chi, nhưng ứng dụng sập trước transaction 2
        WithdrawalRecorder.Started started = withdrawalRecorder.start(userId, 300_000, "wd:" + UUID.randomUUID());
        PaymentTransaction payment = started.payment();
        gateway.payout(payment.getOrderCode(), payment.getAmount());
        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        age(payment.getId(), 6);

        reconciliationJob.run();

        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 0));
    }

    @Test
    @DisplayName("Chạy đối soát lại không xử lý hai lần")
    void reconciliationIsIdempotent() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        gateway.markPaidByCustomer(payment.getOrderCode());
        age(payment.getId(), 31);

        reconciliationJob.run();
        reconciliationJob.run();

        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
        assertThat(ledgerRows("deposit:" + payment.getOrderCode())).isEqualTo(1);
    }
}