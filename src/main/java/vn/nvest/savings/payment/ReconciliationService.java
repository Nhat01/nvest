package vn.nvest.savings.payment;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.nvest.savings.common.PaymentAmountMismatchException;
import vn.nvest.savings.common.PaymentNotFoundException;

/**
 * Đối soát một giao dịch bằng cách hỏi lại cổng (R31, R60). CỐ Ý không có @Transactional:
 * hỏi cổng nằm ngoài transaction, việc ghi nhận đi qua các bean có transaction riêng.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** R31: lệnh nạp PENDING quá 30 phút thì hỏi lại cổng. */
    public static final Duration DEPOSIT_PENDING_TIMEOUT = Duration.ofMinutes(30);
    /** R60: lệnh rút PENDING/UNKNOWN quá 5 phút thì hỏi lại cổng. */
    public static final Duration WITHDRAWAL_RECHECK_AFTER = Duration.ofMinutes(5);
    /** R60: cổng báo không có dấu vết chỉ được coi là thất bại khi lệnh rút đã quá thời gian này. */
    public static final Duration NOT_FOUND_GRACE = Duration.ofHours(2);

    public enum Outcome {
        RESOLVED, STILL_OPEN, ALREADY_SETTLED
    }

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentGateway gateway;
    private final DepositService depositService;
    private final WithdrawalRecorder withdrawalRecorder;
    private final Clock clock;

    public ReconciliationService(PaymentTransactionRepository paymentRepository,
                                 PaymentGateway gateway,
                                 DepositService depositService,
                                 WithdrawalRecorder withdrawalRecorder,
                                 Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.depositService = depositService;
        this.withdrawalRecorder = withdrawalRecorder;
        this.clock = clock;
    }

    public Outcome reconcile(long paymentId) {
        PaymentTransaction payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Unknown payment: " + paymentId));
        if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.UNKNOWN) {
            return Outcome.ALREADY_SETTLED;
        }

        QueryResult result = gateway.queryStatus(payment.getOrderCode());   // hỏi cổng, ngoài mọi transaction
        return payment.getDirection() == PaymentDirection.DEPOSIT
                ? reconcileDeposit(payment, result)
                : reconcileWithdrawal(payment, result);
    }

    private Outcome reconcileDeposit(PaymentTransaction payment, QueryResult result) {
        switch (result.status()) {
            case SUCCEEDED -> {
                // Webhook bị lỡ nhưng khách đã trả: cộng tiền (kiểm số tiền nằm trong confirmFromGateway)
                depositService.confirmFromGateway(payment.getOrderCode(), GatewayStatus.SUCCEEDED,
                        result.amount(), result.providerRef(), null);
                return Outcome.RESOLVED;
            }
            case FAILED, NOT_FOUND -> {
                // R31: cổng báo thất bại hoặc không có dấu vết thì đánh dấu FAILED
                depositService.confirmFromGateway(payment.getOrderCode(), GatewayStatus.FAILED,
                        payment.getAmount(), null, null);
                return Outcome.RESOLVED;
            }
            default -> {
                return Outcome.STILL_OPEN;   // cổng vẫn báo đang chờ khách trả
            }
        }
    }

    private Outcome reconcileWithdrawal(PaymentTransaction payment, QueryResult result) {
        switch (result.status()) {
            case SUCCEEDED -> {
                if (result.amount() != payment.getAmount()) {
                    throw new PaymentAmountMismatchException("Withdrawal " + payment.getOrderCode()
                            + ": gateway paid " + result.amount() + " but the order is for " + payment.getAmount());
                }
                withdrawalRecorder.applyPayoutResult(payment.getId(), GatewayStatus.SUCCEEDED, result.providerRef());
                return Outcome.RESOLVED;
            }
            case FAILED -> {
                withdrawalRecorder.applyPayoutResult(payment.getId(), GatewayStatus.FAILED, result.providerRef());
                return Outcome.RESOLVED;
            }
            case NOT_FOUND -> {
                // Không có dấu vết có thể là "chưa nhận lệnh" hoặc "đang xử lý". Chỉ kết luận sau thời gian chờ (R60).
                Duration age = Duration.between(payment.getCreatedAt(), clock.instant());
                if (age.compareTo(NOT_FOUND_GRACE) >= 0) {
                    log.warn("Withdrawal {} has no trace at the gateway after {}: releasing the hold",
                            payment.getOrderCode(), age);
                    withdrawalRecorder.applyPayoutResult(payment.getId(), GatewayStatus.FAILED, null);
                    return Outcome.RESOLVED;
                }
                return Outcome.STILL_OPEN;
            }
            default -> {
                return Outcome.STILL_OPEN;
            }
        }
    }
}