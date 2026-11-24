package vn.nvest.savings.wallet;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.ClockConfig;
import vn.nvest.savings.common.CurrentUser;

@RestController
@RequestMapping("/api/wallet")
@Tag(name = "Wallet", description = "Số dư và sao kê của chính bạn")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public BalanceResponse balance(@AuthenticationPrincipal Jwt jwt) {
        WalletService.WalletBalance balance = walletService.getBalance(CurrentUser.id(jwt));
        return new BalanceResponse(balance.available(), balance.held());
    }

    /**
     * Lịch sử giao dịch có lọc và phân trang, mới nhất trước. page bắt đầu từ 0, size tối đa 100 (R68).
     * Các tham số lọc đều tuỳ chọn; bỏ trống nghĩa là không lọc theo tiêu chí đó.
     */
    @GetMapping("/transactions")
    public TransactionHistoryPage statement(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) LedgerTxType type,
            @RequestParam(required = false) BalanceType balanceType,
            @RequestParam(required = false) TransactionDirection direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(name = "q", required = false) String keyword) {
        TransactionFilter filter = new TransactionFilter(
                type, balanceType, direction, from, to, minAmount, maxAmount, keyword);
        return walletService.getStatement(CurrentUser.id(jwt), filter, page, size);
    }

    /**
     * Export CSV với cùng bộ lọc như statement. Tối đa 10.000 dòng;
     * header X-Export-Truncated cho biết dữ liệu có bị cắt bớt hay không.
     */
    @GetMapping(value = "/transactions/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) LedgerTxType type,
            @RequestParam(required = false) BalanceType balanceType,
            @RequestParam(required = false) TransactionDirection direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(name = "q", required = false) String keyword) {
        TransactionFilter filter = new TransactionFilter(
                type, balanceType, direction, from, to, minAmount, maxAmount, keyword);
        TransactionExport export = walletService.exportStatement(CurrentUser.id(jwt), filter);
        byte[] csv = TransactionCsvExporter.toCsv(export.lines());
        String filename = "transactions-" + LocalDate.now(ClockConfig.BUSINESS_ZONE) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Export-Truncated", Boolean.toString(export.truncated()))
                .header("X-Export-Rows", Integer.toString(export.lines().size()))
                .body(csv);
    }
}
