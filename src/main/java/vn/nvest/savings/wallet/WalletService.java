package vn.nvest.savings.wallet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.WalletNotFoundException;

import java.util.List;

@Service
public class WalletService {

    public record WalletBalance(long available, long held) {
    }

    private final WalletRepository walletRepository;
    private final LedgerService ledgerService;

    public WalletService(WalletRepository walletRepository, LedgerService ledgerService) {
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public Wallet createUserWallet(long userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(Wallet.forUser(userId)));
    }

    @Transactional(readOnly = true)
    public WalletBalance getBalance(long userId) {
        Wallet wallet = userWallet(userId);
        return new WalletBalance(wallet.getAvailableBalance(), wallet.getHeldBalance());
    }

    /** Tiền từ bên ngoài vào ví user. */
    @Transactional
    public LedgerTransaction deposit(long userId, long amount, String idempotencyKey) {
        requirePositive(amount);
        long userWalletId = userWalletId(userId);
        long externalId = systemWalletId(WalletType.PLATFORM_EXTERNAL);
        return ledgerService.post(LedgerTxType.DEPOSIT, idempotencyKey, "DEPOSIT", null, "Deposit",
                List.of(new LedgerLine(externalId, BalanceType.AVAILABLE, -amount),
                        new LedgerLine(userWalletId, BalanceType.AVAILABLE, amount)));
    }

    /** Rút bước 1: chuyển tiền từ AVAILABLE sang HELD (R29). */
    @Transactional
    public LedgerTransaction holdForWithdrawal(long userId, long amount, String idempotencyKey) {
        requirePositive(amount);
        long walletId = userWalletId(userId);
        return ledgerService.post(LedgerTxType.WITHDRAW_HOLD, idempotencyKey, "WITHDRAWAL", null, "Hold for withdrawal",
                List.of(new LedgerLine(walletId, BalanceType.AVAILABLE, -amount),
                        new LedgerLine(walletId, BalanceType.HELD, amount)));
    }

    /** Rút thất bại rõ ràng: trả tiền từ HELD về AVAILABLE. */
    @Transactional
    public LedgerTransaction releaseHold(long userId, long amount, String idempotencyKey) {
        requirePositive(amount);
        long walletId = userWalletId(userId);
        return ledgerService.post(LedgerTxType.WITHDRAW_RELEASE, idempotencyKey, "WITHDRAWAL", null, "Release hold",
                List.of(new LedgerLine(walletId, BalanceType.HELD, -amount),
                        new LedgerLine(walletId, BalanceType.AVAILABLE, amount)));
    }

    /** Rút thành công: tiền đang HELD chuyển hẳn ra bên ngoài. */
    @Transactional
    public LedgerTransaction confirmWithdrawal(long userId, long amount, String idempotencyKey) {
        requirePositive(amount);
        long userWalletId = userWalletId(userId);
        long externalId = systemWalletId(WalletType.PLATFORM_EXTERNAL);
        return ledgerService.post(LedgerTxType.WITHDRAW_CONFIRM, idempotencyKey, "WITHDRAWAL", null, "Confirm withdrawal",
                List.of(new LedgerLine(userWalletId, BalanceType.HELD, -amount),
                        new LedgerLine(externalId, BalanceType.AVAILABLE, amount)));
    }

    private Wallet userWallet(long userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("No wallet for user " + userId));
    }

    private long userWalletId(long userId) {
        return walletRepository.findIdByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("No wallet for user " + userId));
    }

    private long systemWalletId(WalletType type) {
        return walletRepository.findIdByWalletType(type)
                .orElseThrow(() -> new WalletNotFoundException("System wallet missing: " + type));
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}