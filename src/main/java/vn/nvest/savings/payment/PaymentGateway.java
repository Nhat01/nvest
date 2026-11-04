package vn.nvest.savings.payment;

/** Ranh giới với bên thứ ba. Đổi nhà cung cấp = viết thêm một implementation, không đụng nghiệp vụ. */
public interface PaymentGateway {

    String providerName();

    /** Tạo phiên thanh toán cho đơn nạp tiền. */
    CheckoutSession createCheckout(String orderCode, long amount);

    /**
     * Chi tiền (rút). Trả kết quả rõ ràng, hoặc ném GatewayTimeoutException nếu không biết kết quả.
     * Gọi lại với cùng orderCode không được chi hai lần.
     */
    PayoutResult payout(String orderCode, long amount);

    /** Hỏi lại trạng thái một đơn, dùng cho job đối soát. */
    QueryResult queryStatus(String orderCode);

    /** Xác minh chữ ký của webhook trên nội dung thô. */
    boolean verifySignature(String payload, String signature);
}