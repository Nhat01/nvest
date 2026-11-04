package vn.nvest.savings.payment;

public record QueryResult(GatewayStatus status, long amount, String providerRef) {
}
