package vn.nvest.savings.payment;

/** Nội dung webhook mà cổng gửi về. */
public record WebhookEvent(String orderCode, String providerRef, GatewayStatus status, long amount) {
}
