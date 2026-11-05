package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.InsufficientFundsException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;
import vn.nvest.savings.support.ParallelRunner;
import vn.nvest.savings.wallet.WalletService;

class WithdrawalServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private MockPaymentGateway gateway;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    private PaymentTransaction withdraw(long userId, long amount) {
        return withdrawalService.withdraw(userId, amount, "wd:" + UUID.randomUUID());
    }

    private PaymentTransaction reload(long paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private long ledgerRows(String idempotencyKey) {
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Long.class, idempotencyKey);
        return rows;
    }

    private long externalBalance() {
        Long balance = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE wallet_type = 'PLATFORM_EXTERNAL'", Long.class);
        return balance;
    }

    @Test
    @DisplayName("R29: rút thành công: hold rồi confirm, tiền ra khỏi hệ thống, sổ cái có tham chiếu về lệnh rút")
    void successfulWithdrawal() {
        long userId = createUserWithDeposit(1_000_000);
        long externalBefore = externalBalance();

        PaymentTransaction payment = withdraw(userId, 300_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getOrderCode()).startsWith("WDR-");
        assertThat(payment.getProviderRef()).isNotBlank();
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 0));
        assertThat(externalBalance() - externalBefore).isEqualTo(300_000);
        Map<String, Object> hold = jdbc.queryForMap(
                "SELECT tx_type, reference_type, reference_id FROM ledger_transactions WHERE idempotency_key = ?",
                "withdraw-hold:" + payment.getOrderCode());
        assertThat(hold.get("tx_type")).isEqualTo("WITHDRAW_HOLD");
        assertThat(hold.get("reference_type")).isEqualTo("PAYMENT");
        assertThat(hold.get("reference_id")).isEqualTo(payment.getId());
        assertThat(ledgerRows("withdraw-confirm:" + payment.getOrderCode())).isEqualTo(1);
        assertThat(ledgerRows("withdraw-release:" + payment.getOrderCode())).isZero();
    }

    @Test
    @DisplayName("R29: cổng từ chối rõ ràng thì trả tiền về ví (release)")
    void gatewayRejectsReleasesTheHold() {
        long userId = createUserWithDeposit(1_000_000);
        gateway.scriptNextPayout(MockPaymentGateway.PayoutBehavior.FAIL);

        PaymentTransaction payment = withdraw(userId, 300_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(1_000_000, 0));
        assertThat(ledgerRows("withdraw-release:" + payment.getOrderCode())).isEqualTo(1);
        assertThat(ledgerRows("withdraw-confirm:" + payment.getOrderCode())).isZero();
    }

    @Test
    @DisplayName("R30: timeout (cổng chưa nhận lệnh) thì UNKNOWN và tiền vẫn bị giữ, KHÔNG tự trả lại")
    void timeoutKeepsTheMoneyHeld() {
        long userId = createUserWithDeposit(1_000_000);
        gateway.scriptNextPayout(MockPaymentGateway.PayoutBehavior.TIMEOUT_NOT_EXECUTED);

        PaymentTransaction payment = withdraw(userId, 300_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 300_000));
        assertThat(ledgerRows("withdraw-release:" + payment.getOrderCode())).isZero();
        assertThat(ledgerRows("withdraw-confirm:" + payment.getOrderCode())).isZero();
    }

    @Test
    @DisplayName("R30: timeout nhưng cổng ĐÃ chi tiền: tiền vẫn bị giữ, nếu tự trả lại thì user nhận hai lần")
    void timeoutAfterGatewayPaidStillKeepsTheMoneyHeld() {
        long userId = createUserWithDeposit(1_000_000);
        gateway.scriptNextPayout(MockPaymentGateway.PayoutBehavior.TIMEOUT_BUT_EXECUTED);

        PaymentTransaction payment = withdraw(userId, 300_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 300_000));
        assertThat(gateway.queryStatus(payment.getOrderCode()).status()).isEqualTo(GatewayStatus.SUCCEEDED);   // thực tế đã chi
        assertThat(ledgerRows("withdraw-release:" + payment.getOrderCode())).isZero();
    }

    @Test
    @DisplayName("Không đủ số dư thì hủy sạch: không có lệnh, tiền nguyên vẹn, cổng không bị gọi")
    void insufficientFundsLeavesNothingBehind() {
        long userId = createUserWithDeposit(1_000_000);

        assertThatThrownBy(() -> withdraw(userId, 2_000_000)).isInstanceOf(InsufficientFundsException.class);

        Long payments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE user_id = ?", Long.class, userId);
        assertThat(payments).isZero();
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(1_000_000, 0));
    }

    @Test
    @DisplayName("R57: rút tối thiểu 10.000đ, tối đa 100.000.000đ")
    void withdrawalLimits() {
        long userId = createUserWithDeposit(1_000_000);

        assertThatThrownBy(() -> withdraw(userId, 9_999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withdraw(userId, 100_000_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R54: gửi lại cùng key thì trả lệnh cũ, chỉ giữ và chỉ chi một lần")
    void sameKeyIsProcessedOnce() {
        long userId = createUserWithDeposit(1_000_000);
        String key = "wd:" + UUID.randomUUID();

        PaymentTransaction first = withdrawalService.withdraw(userId, 300_000, key);
        PaymentTransaction second = withdrawalService.withdraw(userId, 300_000, key);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 0));
        assertThat(ledgerRows("withdraw-hold:" + first.getOrderCode())).isEqualTo(1);
        assertThat(ledgerRows("withdraw-confirm:" + first.getOrderCode())).isEqualTo(1);
        assertThatThrownBy(() -> withdrawalService.withdraw(userId, 400_000, key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("R54: 8 request cùng key đến cùng lúc: cùng một lệnh, chỉ giữ và chi một lần")
    void concurrentRequestsWithSameKeyProcessOnce() throws Exception {
        long userId = createUserWithDeposit(1_000_000);
        String key = "wd:" + UUID.randomUUID();

        var results = ParallelRunner.run(8, () -> withdrawalService.withdraw(userId, 300_000, key));

        assertThat(results.stream().map(PaymentTransaction::getId).distinct().count()).isEqualTo(1);
        long paymentId = results.get(0).getId();
        assertThat(reload(paymentId).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(700_000, 0));
        assertThat(ledgerRows("withdraw-hold:" + reload(paymentId).getOrderCode())).isEqualTo(1);
    }

    @Test
    @DisplayName("R29: 8 lệnh rút khác key cùng lúc từ ví 1.000.000đ, mỗi lệnh 300.000đ: đúng 3 lệnh thành công")
    void concurrentWithdrawalsNeverExceedBalance() throws Exception {
        long userId = createUserWithDeposit(1_000_000);
        AtomicInteger sequence = new AtomicInteger();

        var results = ParallelRunner.run(8, () -> {
            try {
                withdrawalService.withdraw(userId, 300_000, "wd:" + userId + ":" + sequence.incrementAndGet());
                return true;
            } catch (InsufficientFundsException e) {
                return false;
            }
        });

        assertThat(results.stream().filter(ok -> ok).count()).isEqualTo(3);
        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(100_000, 0));
    }
}