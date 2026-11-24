package vn.nvest.savings.savings;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.CurrentUser;

@RestController
@RequestMapping("/api/savings")
@Tag(name = "Savings", description = "Gói tiết kiệm: mở, xem, đổi tùy chọn đáo hạn, rút trước hạn")
public class SavingsController {

    private final SavingsService savingsService;
    private final EarlyWithdrawalService earlyWithdrawalService;
    private final InterestAccrualService accrualService;
    private final SavingsSummaryService summaryService;
    private final SavingsSimulationService simulationService;

    public SavingsController(SavingsService savingsService, EarlyWithdrawalService earlyWithdrawalService,
                             InterestAccrualService accrualService, SavingsSummaryService summaryService,
                             SavingsSimulationService simulationService) {
        this.savingsService = savingsService;
        this.earlyWithdrawalService = earlyWithdrawalService;
        this.accrualService = accrualService;
        this.summaryService = summaryService;
        this.simulationService = simulationService;
    }

    @GetMapping("/products")
    public List<ProductResponse> products() {
        return savingsService.listOnSaleProducts().stream().map(ProductResponse::from).toList();
    }

    /** Mở gói (R13, R38, R65). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavingsAccountResponse open(@AuthenticationPrincipal Jwt jwt,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody OpenSavingsRequest request) {
        SavingsAccount account = savingsService.open(CurrentUser.id(jwt), request.productCode(),
                request.amount(), request.maturityOption(), idempotencyKey);
        return toResponse(account, null);
    }

    @GetMapping
    public List<SavingsAccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        List<SavingsAccount> accounts = savingsService.listByUser(CurrentUser.id(jwt));
        Map<Long, SavingsProduct> products = savingsService.productsOf(accounts);
        return accounts.stream()
                .map(account -> SavingsAccountResponse.from(account, products.get(account.getProductId()), null))
                .toList();
    }

    /** Tổng hợp các gói đang chạy của chính user: gốc, lãi tạm tính, lãi dự kiến cả kỳ (R11). */
    @GetMapping("/summary")
    public SavingsSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return summaryService.summarize(CurrentUser.id(jwt));
    }

    /** Chi tiết một gói, kèm lãi tạm tính đã ghi đến hết hôm qua (R11, R40). */
    @GetMapping("/{id}")
    public SavingsAccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        SavingsAccount account = savingsService.getForUser(CurrentUser.id(jwt), id);
        return toResponse(account, accrualService.getAccruedInterest(id));
    }

    /**
     * Máy tính lãi cho một sản phẩm, không tạo gói. amount phải trong khoảng R3.
     * startDate/asOfDate là ngày nghiệp vụ Việt Nam; bỏ trống thì lấy hôm nay.
     */
    @GetMapping("/products/{code}/simulation")
    public SavingsSimulationResponse simulateProduct(
            @PathVariable String code,
            @RequestParam long amount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return simulationService.simulateProduct(code, amount, startDate, asOfDate);
    }

    /** Mô phỏng rút trước hạn cho gói của chính user; không đổi trạng thái hay số dư. */
    @GetMapping("/{id}/early-withdrawal-simulation")
    public SavingsSimulationResponse simulateEarlyWithdrawal(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return simulationService.simulateAccount(CurrentUser.id(jwt), id, asOfDate);
    }

    /** Đổi cách xử lý khi đáo hạn, trước ngày đáo hạn (R16, R48). */
    @PatchMapping("/{id}/maturity-option")
    public SavingsAccountResponse changeMaturityOption(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                       @Valid @RequestBody ChangeOptionRequest request) {
        SavingsAccount account = savingsService.changeMaturityOption(
                CurrentUser.id(jwt), id, request.maturityOption());
        return toResponse(account, null);
    }

    /** Rút trước hạn toàn bộ gói (R22, R23, R51). Gửi lại thì trả biên lai cũ (R52). */
    @PostMapping("/{id}/early-withdrawal")
    public SettlementResponse withdrawEarly(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return SettlementResponse.from(earlyWithdrawalService.withdraw(CurrentUser.id(jwt), id));
    }

    private SavingsAccountResponse toResponse(SavingsAccount account, Long accruedInterest) {
        SavingsProduct product = savingsService.productsOf(List.of(account)).get(account.getProductId());
        return SavingsAccountResponse.from(account, product, accruedInterest);
    }
}
