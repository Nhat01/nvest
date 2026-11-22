package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

@TestPropertySource(properties = {
        "nvest.payment.provider=vnpay",
        "nvest.payment.vnpay.tmn-code=TESTCODE",
        "nvest.payment.vnpay.hash-secret=test-secret",
        "nvest.payment.vnpay.return-url=http://localhost:3000/payment/result"})
class VnPayIpnIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepositService depositService;
    @Autowired private VnPayGateway gateway;

    private PaymentTransaction newDeposit(long userId, long amount) {
        return depositService.createDeposit(userId, amount, "dep:" + UUID.randomUUID()).payment();
    }

    private ResultActions ipn(String orderCode, long vnd, String responseCode, boolean validSignature)
            throws Exception {
        Map<String, String> p = new TreeMap<>();
        p.put("vnp_TmnCode", "TESTCODE");
        p.put("vnp_TxnRef", orderCode);
        p.put("vnp_Amount", String.valueOf(vnd * 100));
        p.put("vnp_ResponseCode", responseCode);
        p.put("vnp_TransactionStatus", responseCode);
        p.put("vnp_TransactionNo", "1" + Math.abs(orderCode.hashCode()));
        p.put("vnp_SecureHash", validSignature ? gateway.sign(p) : "deadbeef");
        var request = get("/webhooks/vnpay/ipn");
        p.forEach(request::param);
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("IPN hợp lệ cộng tiền, gửi trùng vẫn RspCode 00 và chỉ cộng một lần")
    void validIpnCreditsOnce() throws Exception {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        assertThat(payment.getProvider()).isEqualTo("VNPAY");

        ipn(payment.getOrderCode(), 500_000, "00", true).andExpect(jsonPath("$.RspCode").value("00"));
        ipn(payment.getOrderCode(), 500_000, "00", true).andExpect(jsonPath("$.RspCode").value("00"));

        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("Sai chữ ký 97, sai số tiền 04, đơn lạ 01; ví không đổi")
    void rejectedIpnsMoveNoMoney() throws Exception {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);

        ipn(payment.getOrderCode(), 500_000, "00", false).andExpect(jsonPath("$.RspCode").value("97"));
        ipn(payment.getOrderCode(), 400_000, "00", true).andExpect(jsonPath("$.RspCode").value("04"));
        ipn("DEP-NOPE", 1_000, "00", true).andExpect(jsonPath("$.RspCode").value("01"));

        assertThat(walletService.getBalance(userId).available()).isZero();
    }

    @Test
    @DisplayName("Khách hủy thanh toán (mã 24) thì đơn FAILED, không cộng tiền")
    void cancelledPaymentFails() throws Exception {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);

        ipn(payment.getOrderCode(), 500_000, "24", true).andExpect(jsonPath("$.RspCode").value("00"));

        assertThat(walletService.getBalance(userId).available()).isZero();
    }
}