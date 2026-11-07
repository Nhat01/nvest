package vn.nvest.savings.wallet;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.CurrentUser;

/**
 * CHỈ TỒN TẠI khi chạy với profile "dev" (R71): nạp tiền ảo để thử trên Swagger.
 * Ở môi trường thật bean này không được tạo, nên đường dẫn không tồn tại.
 */
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
@Tag(name = "Dev", description = "CHỈ có ở profile dev: nạp tiền ảo để thử")
public class DevCreditController {

    private static final Logger log = LoggerFactory.getLogger(DevCreditController.class);
    private static final long MAX_CREDIT = 100_000_000;

    private final WalletService walletService;

    public DevCreditController(WalletService walletService) {
        this.walletService = walletService;
        log.warn("Profile 'dev' is active: /api/dev/credit can mint test money. NEVER enable this in production.");
    }

    @PostMapping("/credit")
    public BalanceResponse credit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DevCreditRequest request) {
        if (request.amount() > MAX_CREDIT) {
            throw new IllegalArgumentException("Dev credit is limited to " + MAX_CREDIT + " per call (R71)");
        }
        long userId = CurrentUser.id(jwt);
        // Vẫn đi qua sổ cái hai chiều như một lần nạp thật: ví EXTERNAL ghi âm, ví user ghi dương (R37)
        walletService.deposit(userId, request.amount(), "dev-credit:" + UUID.randomUUID(), "DEV", null);
        WalletService.WalletBalance balance = walletService.getBalance(userId);
        return new BalanceResponse(balance.available(), balance.held());
    }
}