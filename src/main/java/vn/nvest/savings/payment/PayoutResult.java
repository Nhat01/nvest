package vn.nvest.savings.payment;

/** Kết quả rõ ràng của lệnh chi tiền: status chỉ là SUCCEEDED hoặc FAILED. */
public record PayoutResult(GatewayStatus status, String providerRef, String message) {
}
