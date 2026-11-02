package vn.nvest.savings.savings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.ProductNotAvailableException;
import vn.nvest.savings.common.ProductNotFoundException;
import vn.nvest.savings.wallet.WalletService;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class SavingsService {

    private static final int MAX_KEY_LENGTH = 80;   // chừa chỗ cho tiền tố "open-savings:" trong sổ cái

    private final SavingsProductRepository productRepository;
    private final SavingsAccountRepository accountRepository;
    private final WalletService walletService;
    private final Clock clock;

    public SavingsService(SavingsProductRepository productRepository,
                          SavingsAccountRepository accountRepository,
                          WalletService walletService,
                          Clock clock) {
        this.productRepository = productRepository;
        this.accountRepository = accountRepository;
        this.walletService = walletService;
        this.clock = clock;
    }

    /**
     * Mở gói tiết kiệm (R13): tạo gói và chuyển gốc sang ví custody trong CÙNG một transaction.
     * Gửi lại cùng idempotencyKey và cùng nội dung thì trả về gói đã mở (R38).
     */
    @Transactional
    public SavingsAccount open(long userId, String productCode, long amount,
                               MaturityOption option, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey is required, max " + MAX_KEY_LENGTH + " chars (R38)");
        }
        Objects.requireNonNull(option, "maturity option is required");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        SavingsProduct product = productRepository.findByCode(productCode)
                .orElseThrow(() -> new ProductNotFoundException("Unknown product: " + productCode));

        // Gửi lại yêu cầu cũ: trả gói đã mở, không trừ tiền lần hai
        Optional<SavingsAccount> existing = accountRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            SavingsAccount account = existing.get();
            if (!account.getUserId().equals(userId)
                    || !account.getProductId().equals(product.getId())
                    || account.getPrincipal() != amount) {
                throw new IdempotencyConflictException(
                        "Key '" + idempotencyKey + "' was already used for a different request");
            }
            return account;
        }

        if (!product.isOnSale()) {
            throw new ProductNotAvailableException("Product is not on sale: " + productCode);
        }
        product.validateAmount(amount);

        // Tạo gói trước để có id, rồi chuyển gốc và gắn tham chiếu sổ cái về gói này
        SavingsAccount account = accountRepository.save(SavingsAccount.open(
                userId, product, amount, option, LocalDate.now(clock), idempotencyKey));
        walletService.moveToCustody(userId, amount, "open-savings:" + idempotencyKey,
                "SAVINGS_ACCOUNT", account.getId());
        return account;
    }

    @Transactional(readOnly = true)
    public List<SavingsAccount> listByUser(long userId) {
        return accountRepository.findByUserIdOrderByIdDesc(userId);
    }
}
