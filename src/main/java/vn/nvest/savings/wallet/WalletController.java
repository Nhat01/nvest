package vn.nvest.savings.wallet;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    /** Sao kê mới nhất trước. page bắt đầu từ 0, size tối đa 100 (R68). */
    @GetMapping("/transactions")
    public List<StatementLine> statement(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return walletService.getStatement(CurrentUser.id(jwt), page, size);
    }
}