package vn.nvest.savings.payment;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.CurrentUser;

@RestController
@RequestMapping("/api")
@Tag(name = "Payments", description = "Nạp và rút tiền qua cổng thanh toán")
public class PaymentController {

    private final DepositService depositService;
    private final WithdrawalService withdrawalService;
    private final PaymentQueryService paymentQueryService;

    public PaymentController(DepositService depositService, WithdrawalService withdrawalService,
                             PaymentQueryService paymentQueryService) {
        this.depositService = depositService;
        this.withdrawalService = withdrawalService;
        this.paymentQueryService = paymentQueryService;
    }

    /** Tạo đơn nạp. Tiền chỉ vào ví khi cổng xác nhận qua webhook (R27). */
    @PostMapping("/deposits")
    public ResponseEntity<PaymentResponse> deposit(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                   @Valid @RequestBody AmountRequest request) {
        DepositService.DepositSession session = depositService.createDeposit(
                CurrentUser.id(jwt), request.amount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(session.payment()));
    }

    /**
     * Rút tiền. 200 nếu đã có kết quả rõ ràng (SUCCEEDED hoặc FAILED);
     * 202 nếu chưa chắc (PENDING hoặc UNKNOWN): tiền đang được giữ, hệ thống sẽ đối soát (R30, R66).
     */
    @PostMapping("/withdrawals")
    public ResponseEntity<PaymentResponse> withdraw(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                    @Valid @RequestBody AmountRequest request) {
        PaymentTransaction payment = withdrawalService.withdraw(
                CurrentUser.id(jwt), request.amount(), idempotencyKey);
        boolean settled = payment.getStatus() == PaymentStatus.SUCCEEDED
                || payment.getStatus() == PaymentStatus.FAILED;
        return ResponseEntity.status(settled ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/payments")
    public List<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return paymentQueryService.list(CurrentUser.id(jwt), page, size).stream()
                .map(PaymentResponse::from).toList();
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return PaymentResponse.from(paymentQueryService.get(CurrentUser.id(jwt), id));
    }
}