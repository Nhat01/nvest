package vn.nvest.savings.payment;

/** Cổng chi tiền (rút). */
public interface PayoutGateway {

    String providerName();

    PayoutResult payout(String orderCode, long amount);   // timeout thì ném GatewayTimeoutException (R59)

    QueryResult queryStatus(String orderCode);
}