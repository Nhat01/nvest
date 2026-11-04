package vn.nvest.savings.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.InvalidSignatureException;
import vn.nvest.savings.common.PaymentAmountMismatchException;
import vn.nvest.savings.common.PaymentNotFoundException;

@RestController
@RequestMapping("/webhooks")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final DepositService depositService;

    public PaymentWebhookController(DepositService depositService) {
        this.depositService = depositService;
    }

    /** Nhận body DẠNG CHUỖI THÔ để xác minh chữ ký trên đúng những byte cổng đã ký. */
    @PostMapping(path = "/mock", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receiveMockWebhook(
            @RequestBody String body,
            @RequestHeader(name = "X-Signature", required = false) String signature) {
        depositService.handleWebhook(body, signature);
        return ResponseEntity.ok("OK");   // cả trường hợp webhook gửi trùng cũng trả 200 (R28)
    }

    @ExceptionHandler(InvalidSignatureException.class)
    public ResponseEntity<String> onInvalidSignature(InvalidSignatureException e) {
        log.warn("Rejected webhook: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<String> onUnknownOrder(PaymentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(PaymentAmountMismatchException.class)
    public ResponseEntity<String> onAmountMismatch(PaymentAmountMismatchException e) {
        log.error("Webhook amount mismatch: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("amount mismatch");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> onMalformed(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}