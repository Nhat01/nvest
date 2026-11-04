package vn.nvest.savings.payment;

/** Trạng thái giao dịch của mình. UNKNOWN: kết quả mơ hồ, chờ đối soát (R30). */
public enum PaymentStatus {
    PENDING, SUCCEEDED, FAILED, UNKNOWN
}