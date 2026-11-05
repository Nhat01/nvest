package vn.nvest.savings.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.InvalidSignatureException;
import vn.nvest.savings.common.PaymentAmountMismatchException;
import vn.nvest.savings.common.PaymentNotFoundException;
import vn.nvest.savings.wallet.WalletService;

@Service
public class DepositService {

    public static final long MIN_DEPOSIT = 10_000;
    public static final long MAX_DEPOSIT = 100_000_000;
    private static final int MAX_KEY_LENGTH = 80;

    /** Kết quả tạo đơn nạp: giao dịch và đường dẫn để user sang cổng thanh toán. */
    public record DepositSession(PaymentTransaction payment, String checkoutUrl) {
    }

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentGateway gateway;
    private final WalletService walletService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DepositService(PaymentTransactionRepository paymentRepository,
                          PaymentGateway gateway,
                          WalletService walletService,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.walletService = walletService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Bước 1 của nạp tiền: tạo đơn PENDING và phiên thanh toán. Chưa có đồng nào vào ví (R27).
     * Gửi lại cùng idempotencyKey và cùng nội dung thì trả đơn cũ (R54).
     */
    @Transactional
    public DepositSession createDeposit(long userId, long amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey is required, max " + MAX_KEY_LENGTH + " chars (R54)");
        }
        if (amount < MIN_DEPOSIT || amount > MAX_DEPOSIT) {
            throw new IllegalArgumentException(
                    "Deposit must be between " + MIN_DEPOSIT + " and " + MAX_DEPOSIT + " (R27), got " + amount);
        }

        paymentRepository.lockByKey("payment:" + idempotencyKey);
        Optional<PaymentTransaction> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PaymentTransaction payment = existing.get();
            if (payment.getDirection() != PaymentDirection.DEPOSIT
                    || !payment.getUserId().equals(userId)
                    || payment.getAmount() != amount) {
                throw new IdempotencyConflictException(
                        "Key '" + idempotencyKey + "' was already used for a different request");
            }
            return new DepositSession(payment, payment.getCheckoutUrl());
        }

        walletService.requireWallet(userId);   // chỉ kiểm tra bằng id, không nạp entity ví (L2)

        String orderCode = PaymentTransaction.newOrderCode("DEP-");
        PaymentTransaction payment = paymentRepository.save(PaymentTransaction.newDeposit(
                userId, amount, orderCode, gateway.providerName(), idempotencyKey, clock.instant()));
        CheckoutSession session = gateway.createCheckout(orderCode, amount);
        payment.attachCheckout(session.providerRef(), session.checkoutUrl(), clock.instant());
        return new DepositSession(payment, session.checkoutUrl());
    }

    /**
     * Bước 2: xử lý webhook từ cổng. Thứ tự kiểm tra: chữ ký, nội dung, rồi mới đến nghiệp vụ.
     */
    @Transactional
    public PaymentTransaction handleWebhook(String rawBody, String signature) {
        if (!gateway.verifySignature(rawBody, signature)) {
            throw new InvalidSignatureException("Invalid webhook signature");
        }
        WebhookEvent event = parse(rawBody);
        return confirmFromGateway(event.orderCode(), event.status(), event.amount(), event.providerRef(), rawBody);
    }

    /**
     * Áp kết quả mà cổng báo (qua webhook, hoặc qua job đối soát ở 9B) lên đơn nạp tiền.
     * Gọi lặp lại an toàn (R28): đơn đã SUCCEEDED thì không cộng thêm.
     */
    @Transactional
    public PaymentTransaction confirmFromGateway(String orderCode, GatewayStatus status, long amount,
                                                 String providerRef, String rawPayload) {
        // Khóa dòng giao dịch trước, rồi mới đụng tới ví (R50)
        PaymentTransaction payment = paymentRepository.findByOrderCodeForUpdate(orderCode)
                .orElseThrow(() -> new PaymentNotFoundException("Unknown order: " + orderCode));
        if (payment.getDirection() != PaymentDirection.DEPOSIT) {
            throw new IllegalArgumentException("Order " + orderCode + " is not a deposit");
        }

        if (status == GatewayStatus.SUCCEEDED) {
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                return payment;   // webhook gửi trùng: trả OK nhưng không cộng thêm (R28)
            }
            if (amount != payment.getAmount()) {
                throw new PaymentAmountMismatchException("Order " + orderCode + ": gateway reports "
                        + amount + " but the order is for " + payment.getAmount() + " (R56)");
            }
            // Cộng tiền và đổi trạng thái trong cùng một transaction. Khóa sổ cái chống cộng trùng (R35).
            walletService.deposit(payment.getUserId(), payment.getAmount(),
                    "deposit:" + orderCode, "PAYMENT", payment.getId());
            payment.markSucceeded(providerRef, rawPayload, clock.instant());
        } else if (status == GatewayStatus.FAILED) {
            if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.UNKNOWN) {
                payment.markFailed("GATEWAY_REPORTED_FAILURE", rawPayload, clock.instant());
            }
            // Đã SUCCEEDED hoặc đã FAILED: bỏ qua (R55)
        } else {
            throw new IllegalArgumentException("Unsupported gateway status: " + status);
        }
        return payment;
    }

    private WebhookEvent parse(String rawBody) {
        try {
            WebhookEvent event = objectMapper.readValue(rawBody, WebhookEvent.class);
            if (event.orderCode() == null || event.orderCode().isBlank() || event.status() == null) {
                throw new IllegalArgumentException("Malformed webhook: orderCode and status are required");
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed webhook payload", e);
        }
    }
}