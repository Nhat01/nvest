package vn.nvest.savings.payment;

import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.PaymentNotFoundException;
import vn.nvest.savings.outbox.EventTypes;
import vn.nvest.savings.outbox.OutboxService;
import vn.nvest.savings.outbox.Topics;
import vn.nvest.savings.outbox.WithdrawalSettledEvent;
import vn.nvest.savings.wallet.WalletService;

/** Các bước CÓ transaction của lệnh rút. Việc gọi cổng nằm ở WithdrawalService, ngoài mọi transaction (R58). */
@Service
public class WithdrawalRecorder {

    public static final long MIN_WITHDRAWAL = 10_000;
    public static final long MAX_WITHDRAWAL = 100_000_000;
    private static final int MAX_KEY_LENGTH = 80;

    /** created = false nghĩa là yêu cầu gửi lại, trả lệnh cũ. */
    public record Started(PaymentTransaction payment, boolean created) {
    }

    private final PaymentTransactionRepository paymentRepository;
    private final PayoutGateway gateway;
    private final WalletService walletService;
    private final Clock clock;
    private final OutboxService outboxService;

    public WithdrawalRecorder(PaymentTransactionRepository paymentRepository,
                              PayoutGateway gateway,
                              WalletService walletService,
                              Clock clock, OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.walletService = walletService;
        this.clock = clock;
        this.outboxService = outboxService;
    }

    /**
     * Transaction 1: tạo lệnh PENDING và giữ tiền (AVAILABLE → HELD). Không đủ tiền thì hủy sạch, không để lại lệnh nào.
     */
    @Transactional
    public Started start(long userId, long amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey is required, max " + MAX_KEY_LENGTH + " chars (R54)");
        }
        if (amount < MIN_WITHDRAWAL || amount > MAX_WITHDRAWAL) {
            throw new IllegalArgumentException(
                    "Withdrawal must be between " + MIN_WITHDRAWAL + " and " + MAX_WITHDRAWAL + " (R57), got " + amount);
        }

        paymentRepository.lockByKey("payment:" + idempotencyKey);
        Optional<PaymentTransaction> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PaymentTransaction payment = existing.get();
            if (payment.getDirection() != PaymentDirection.WITHDRAW
                    || !payment.getUserId().equals(userId)
                    || payment.getAmount() != amount) {
                throw new IdempotencyConflictException(
                        "Key '" + idempotencyKey + "' was already used for a different request");
            }
            return new Started(payment, false);
        }

        walletService.requireWallet(userId);   // chỉ kiểm tra bằng id, không nạp entity ví (L2)

        String orderCode = PaymentTransaction.newOrderCode("WDR-");
        PaymentTransaction payment = paymentRepository.save(PaymentTransaction.newWithdrawal(
                userId, amount, orderCode, gateway.providerName(), idempotencyKey, clock.instant()));
        // Không đủ số dư: InsufficientFundsException làm hủy cả transaction, kể cả dòng payment vừa lưu
        walletService.holdForWithdrawal(userId, amount, "withdraw-hold:" + orderCode, "PAYMENT", payment.getId());
        return new Started(payment, true);
    }

    /**
     * Transaction 2: áp kết quả RÕ RÀNG của cổng. SUCCEEDED thì confirm, FAILED thì release (R29).
     * Gọi lặp lại an toàn: lệnh đã chốt thì trả nguyên trạng.
     */
    @Transactional
    public PaymentTransaction applyPayoutResult(long paymentId, GatewayStatus status, String providerRef) {
        PaymentTransaction payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Unknown payment: " + paymentId));
        if (payment.getDirection() != PaymentDirection.WITHDRAW) {
            throw new IllegalArgumentException("Payment " + paymentId + " is not a withdrawal");
        }
        if (payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.FAILED) {
            return payment;   // đã chốt rồi
        }

        if (status == GatewayStatus.SUCCEEDED) {
            walletService.confirmWithdrawal(payment.getUserId(), payment.getAmount(),
                    "withdraw-confirm:" + payment.getOrderCode(), "PAYMENT", payment.getId());
            payment.markSucceeded(providerRef, null, clock.instant());
        } else if (status == GatewayStatus.FAILED) {
            walletService.releaseHold(payment.getUserId(), payment.getAmount(),
                    "withdraw-release:" + payment.getOrderCode(), "PAYMENT", payment.getId());
            payment.markFailed("GATEWAY_REJECTED", null, clock.instant());
        } else {
            throw new IllegalArgumentException("Only SUCCEEDED or FAILED can settle a withdrawal, got " + status);
        }
        outboxService.record(Topics.PAYMENT_EVENTS, EventTypes.WITHDRAWAL_SETTLED,
                "withdrawal-settled:" + payment.getOrderCode(), "user:" + payment.getUserId(),
                new WithdrawalSettledEvent(payment.getId(), payment.getUserId(), payment.getAmount(),
                        payment.getOrderCode(), payment.getStatus().name()));
        return payment;
    }

    /** Kết quả mơ hồ (R30, R59): đánh dấu UNKNOWN, KHÔNG đụng vào tiền đang giữ. */
    @Transactional
    public PaymentTransaction markUnknown(long paymentId, String reason) {
        PaymentTransaction payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Unknown payment: " + paymentId));
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.markUnknown(reason, clock.instant());
        }
        return payment;
    }
}