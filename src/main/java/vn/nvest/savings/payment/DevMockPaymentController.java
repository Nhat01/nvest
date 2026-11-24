package vn.nvest.savings.payment;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.CurrentUser;
import vn.nvest.savings.common.PaymentNotFoundException;

/**
 * CHỈ TỒN TẠI ở profile "dev" với cổng giả: đóng vai khách hàng bấm "đã thanh toán" hoặc "hủy" trên trang cổng.
 * Đi qua đúng đường webhook có ký (R27, R55), chỉ cho chủ đơn thao tác (R64, R68).
 */
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
@ConditionalOnProperty(name = "nvest.payment.provider", havingValue = "mock", matchIfMissing = true)
@Tag(name = "Dev", description = "CHỈ có ở profile dev: giả lập khách thanh toán qua cổng giả")
public class DevMockPaymentController {

    public record SimulatePaymentRequest(@NotNull GatewayStatus result) {
    }

    private final PaymentTransactionRepository paymentRepository;
    private final MockPaymentGateway gateway;
    private final DepositService depositService;

    public DevMockPaymentController(PaymentTransactionRepository paymentRepository,
                                    MockPaymentGateway gateway, DepositService depositService) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.depositService = depositService;
    }

    @PostMapping("/deposits/{orderCode}/simulate")
    public PaymentResponse simulate(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderCode,
                                    @Valid @RequestBody SimulatePaymentRequest request) {
        if (request.result() != GatewayStatus.SUCCEEDED && request.result() != GatewayStatus.FAILED) {
            throw new IllegalArgumentException("result must be SUCCEEDED or FAILED");
        }
        long userId = CurrentUser.id(jwt);
        PaymentTransaction payment = paymentRepository.findByOrderCode(orderCode)
                .filter(p -> p.getUserId().equals(userId) && p.getDirection() == PaymentDirection.DEPOSIT)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + orderCode));
        if (request.result() == GatewayStatus.SUCCEEDED) {
            gateway.markPaidByCustomer(orderCode);
        } else {
            gateway.markFailedByCustomer(orderCode);
        }
        MockPaymentGateway.SignedWebhook webhook = gateway.webhookFor(orderCode, request.result(), payment.getAmount());
        return PaymentResponse.from(depositService.handleWebhook(webhook.payload(), webhook.signature()));
    }
}
