package vn.nvest.savings.payment;

/** Trạng thái một đơn theo cổng thanh toán. NOT_FOUND: cổng không biết đơn này. */
public enum GatewayStatus {
    PENDING, SUCCEEDED, FAILED, NOT_FOUND
}
