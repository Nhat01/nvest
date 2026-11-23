package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {

    @Test
    @DisplayName("providerRef ngẫu nhiên nên không đụng unique index sau khi restart")
    void providerRefsAreUniqueAcrossInstances() {
        MockPaymentGateway first = new MockPaymentGateway("secret", "http://localhost:8080");
        String firstRef = first.createCheckout("DEP-1", 1_000, Instant.now()).providerRef();

        // Instance mới = mô phỏng app restart: bộ đếm trong bộ nhớ đã mất
        MockPaymentGateway afterRestart = new MockPaymentGateway("secret", "http://localhost:8080");
        String secondRef = afterRestart.createCheckout("DEP-2", 1_000, Instant.now()).providerRef();

        assertThat(firstRef).startsWith("MOCK-");
        assertThat(secondRef).startsWith("MOCK-");
        assertThat(firstRef).isNotEqualTo(secondRef);
    }

    @Test
    @DisplayName("checkoutUrl dựng từ base URL cấu hình, bỏ dấu / thừa ở cuối")
    void checkoutUrlUsesConfiguredBase() {
        MockPaymentGateway gateway = new MockPaymentGateway("secret", "http://localhost:18080/");

        CheckoutSession session = gateway.createCheckout("DEP-ABC", 1_000, Instant.now());

        assertThat(session.checkoutUrl()).isEqualTo("http://localhost:18080/mock-gateway/pay/DEP-ABC");
    }
}
