package vn.nvest.savings.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.nvest.savings.common.InvalidStateTransitionException;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private PaymentDirection direction;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "order_code", nullable = false, updatable = false)
    private String orderCode;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "checkout_url")
    private String checkoutUrl;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "raw_payload", columnDefinition = "text")
    private String rawPayload;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Mã đơn duy nhất gửi sang cổng, ví dụ DEP-3F9A1C... (R28). */
    public static String newOrderCode(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    public static PaymentTransaction newDeposit(long userId, long amount, String orderCode,
                                                String provider, String idempotencyKey, Instant now) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.userId = userId;
        payment.direction = PaymentDirection.DEPOSIT;
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        payment.orderCode = orderCode;
        payment.provider = provider;
        payment.idempotencyKey = idempotencyKey;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    public static PaymentTransaction newWithdrawal(long userId, long amount, String orderCode,
                                                   String provider, String idempotencyKey, Instant now) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.userId = userId;
        payment.direction = PaymentDirection.WITHDRAW;
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        payment.orderCode = orderCode;
        payment.provider = provider;
        payment.idempotencyKey = idempotencyKey;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /** R30: kết quả mơ hồ. Tiền vẫn bị giữ, chờ job đối soát. */
    public void markUnknown(String reason, Instant now) {
        if (status == PaymentStatus.UNKNOWN) {
            return;
        }
        if (status != PaymentStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "Payment " + orderCode + " is " + status + " and cannot become UNKNOWN");
        }
        this.status = PaymentStatus.UNKNOWN;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public void attachCheckout(String providerRef, String checkoutUrl, Instant now) {
        this.providerRef = providerRef;
        this.checkoutUrl = checkoutUrl;
        this.updatedAt = now;
    }

    /**
     * Thành công. Được phép từ PENDING hoặc UNKNOWN; riêng với nạp tiền còn được phép từ FAILED
     * vì cổng là nguồn sự thật: thành công đến muộn vẫn phải cộng tiền (R55).
     */
    public void markSucceeded(String providerRef, String rawPayload, Instant now) {
        boolean lateDeposit = direction == PaymentDirection.DEPOSIT && status == PaymentStatus.FAILED;
        if (status != PaymentStatus.PENDING && status != PaymentStatus.UNKNOWN && !lateDeposit) {
            throw new InvalidStateTransitionException(
                    "Payment " + orderCode + " is " + status + " and cannot become SUCCEEDED");
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.failureReason = null;
        if (providerRef != null) {
            this.providerRef = providerRef;
        }
        if (rawPayload != null) {
            this.rawPayload = rawPayload;
        }
        this.updatedAt = now;
    }

    public void markFailed(String reason, String rawPayload, Instant now) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.UNKNOWN) {
            throw new InvalidStateTransitionException(
                    "Payment " + orderCode + " is " + status + " and cannot become FAILED");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        if (rawPayload != null) {
            this.rawPayload = rawPayload;
        }
        this.updatedAt = now;
    }
}