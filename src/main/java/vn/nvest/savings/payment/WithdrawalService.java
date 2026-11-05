package vn.nvest.savings.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Điều phối lệnh rút. CỐ Ý không có @Transactional: gọi cổng phải nằm giữa hai transaction (R58).
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final WithdrawalRecorder recorder;
    private final PaymentGateway gateway;

    public WithdrawalService(WithdrawalRecorder recorder, PaymentGateway gateway) {
        this.recorder = recorder;
        this.gateway = gateway;
    }

    public PaymentTransaction withdraw(long userId, long amount, String idempotencyKey) {
        // Transaction 1: tạo lệnh và giữ tiền, commit xong mới đi tiếp
        WithdrawalRecorder.Started started = recorder.start(userId, amount, idempotencyKey);
        PaymentTransaction payment = started.payment();
        if (!started.created()) {
            return payment;   // R54: gửi lại thì trả lệnh cũ, TUYỆT ĐỐI không gọi cổng lần hai
        }

        // Gọi cổng: không nằm trong transaction nào, có thể chậm hoặc lỗi
        PayoutResult result;
        try {
            result = gateway.payout(payment.getOrderCode(), payment.getAmount());
        } catch (RuntimeException e) {
            // Timeout hay bất kỳ lỗi nào khác: KHÔNG biết cổng đã chi hay chưa. Giữ nguyên tiền (R30, R59).
            log.warn("Payout call for {} ended without a clear result: {}", payment.getOrderCode(), e.toString());
            return recorder.markUnknown(payment.getId(), "GATEWAY_ERROR: " + e.getClass().getSimpleName());
        }

        // Transaction 2: áp kết quả rõ ràng
        return recorder.applyPayoutResult(payment.getId(), result.status(), result.providerRef());
    }
}