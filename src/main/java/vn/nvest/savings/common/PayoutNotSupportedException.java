package vn.nvest.savings.common;

/** Cổng thanh toán hiện tại không hỗ trợ chi tiền (ví dụ VNPAY chỉ có API thu tiền). */
public class PayoutNotSupportedException extends RuntimeException {

    public PayoutNotSupportedException(String message) {
        super(message);
    }
}
