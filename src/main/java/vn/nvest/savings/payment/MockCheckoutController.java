package vn.nvest.savings.payment;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Trang thanh toán giả để đi hết luồng nạp tiền khi chạy với nvest.payment.provider=mock.
 *
 * CHỈ tồn tại ở chế độ mock. Không dùng cho tiền thật: bất kỳ ai có mã đơn đều bấm được
 * "Thanh toán", và tiền chỉ là tiền ảo trong ví. Khi provider=vnpay controller này không được tạo.
 */
@Controller
@RequestMapping("/mock-gateway/pay")
@Hidden   // trang HTML nội bộ của cổng giả, không đưa vào OpenAPI
@ConditionalOnProperty(name = "nvest.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockCheckoutController {

    private final PaymentTransactionRepository paymentRepository;
    private final DepositService depositService;
    private final MockPaymentGateway gateway;

    public MockCheckoutController(PaymentTransactionRepository paymentRepository,
                                  DepositService depositService,
                                  MockPaymentGateway gateway) {
        this.paymentRepository = paymentRepository;
        this.depositService = depositService;
        this.gateway = gateway;
    }

    /** Công khai như trang của cổng thật: người trả tiền không mang token của ứng dụng. */
    @GetMapping(value = "/{orderCode}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> checkout(@PathVariable String orderCode) {
        PaymentTransaction payment = paymentRepository.findByOrderCode(orderCode)
                .filter(p -> p.getDirection() == PaymentDirection.DEPOSIT)
                .orElse(null);
        if (payment == null) {
            return html(HttpStatus.NOT_FOUND, page("Không tìm thấy đơn",
                    "<p>Đơn <code>" + escape(orderCode) + "</code> không tồn tại.</p>"));
        }
        return html(HttpStatus.OK, page("Cổng thanh toán giả", body(payment)));
    }

    @PostMapping("/{orderCode}/pay")
    public String pay(@PathVariable String orderCode) {
        decide(orderCode, GatewayStatus.SUCCEEDED);
        return "redirect:/mock-gateway/pay/" + orderCode;
    }

    @PostMapping("/{orderCode}/cancel")
    public String cancel(@PathVariable String orderCode) {
        decide(orderCode, GatewayStatus.FAILED);
        return "redirect:/mock-gateway/pay/" + orderCode;
    }

    /**
     * Cổng thật sẽ gọi webhook của ta; ở đây ta tự dựng webhook đã ký rồi đưa qua đúng luồng
     * DepositService.handleWebhook để kiểm tra chữ ký và cộng tiền y như thật.
     */
    private void decide(String orderCode, GatewayStatus status) {
        paymentRepository.findByOrderCode(orderCode)
                .filter(p -> p.getDirection() == PaymentDirection.DEPOSIT)
                .filter(p -> p.getStatus() == PaymentStatus.PENDING
                        || p.getStatus() == PaymentStatus.UNKNOWN)
                .ifPresent(payment -> {
                    if (status == GatewayStatus.SUCCEEDED) {
                        gateway.markPaidByCustomer(orderCode);
                    } else {
                        gateway.markFailedByCustomer(orderCode);
                    }
                    MockPaymentGateway.SignedWebhook webhook = gateway.webhookFor(
                            orderCode, payment.getProviderRef(), status, payment.getAmount());
                    depositService.handleWebhook(webhook.payload(), webhook.signature());
                });
    }

    private static String body(PaymentTransaction payment) {
        String amount = String.format(Locale.ROOT, "%,d", payment.getAmount()).replace(',', '.');
        String status = switch (payment.getStatus()) {
            case PENDING -> "Chờ thanh toán";
            case UNKNOWN -> "Chưa rõ kết quả";
            case SUCCEEDED -> "Đã thanh toán";
            case FAILED -> "Đã huỷ / thất bại";
        };

        StringBuilder html = new StringBuilder();
        html.append("<p class=\"banner\">CỔNG GIẢ — chỉ dùng cho dev/demo, không phải tiền thật.</p>")
                .append("<dl><dt>Mã đơn</dt><dd><code>").append(escape(payment.getOrderCode()))
                .append("</code></dd>")
                .append("<dt>Số tiền</dt><dd>").append(amount).append(" đ</dd>")
                .append("<dt>Trạng thái</dt><dd>").append(status).append("</dd></dl>");

        if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.UNKNOWN) {
            String action = "/mock-gateway/pay/" + escape(payment.getOrderCode());
            html.append("<form method=\"post\" action=\"").append(action).append("/pay\">")
                    .append("<button type=\"submit\" class=\"pay\">Thanh toán</button></form>")
                    .append("<form method=\"post\" action=\"").append(action).append("/cancel\">")
                    .append("<button type=\"submit\" class=\"cancel\">Huỷ</button></form>");
        }
        return html.toString();
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(body);
    }

    private static String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="vi">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    body { font-family: system-ui, sans-serif; max-width: 32rem; margin: 3rem auto; padding: 0 1rem; color: #1f2937; }
                    .banner { background: #fef3c7; border: 1px solid #f59e0b; padding: .75rem; border-radius: .5rem; font-weight: 600; }
                    dl { display: grid; grid-template-columns: max-content 1fr; gap: .5rem 1rem; }
                    dt { color: #6b7280; }
                    dd { margin: 0; font-weight: 600; }
                    form { display: inline-block; margin-right: .5rem; }
                    button { padding: .6rem 1.2rem; border: 0; border-radius: .4rem; font-size: 1rem; cursor: pointer; }
                    .pay { background: #16a34a; color: white; }
                    .cancel { background: #e5e7eb; color: #111827; }
                  </style>
                </head>
                <body>
                  <h1>%s</h1>
                  %s
                </body>
                </html>
                """.formatted(escape(title), escape(title), body);
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
