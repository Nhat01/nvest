package vn.nvest.savings.wallet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.WalletNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** Tiền từ bên ngoài vào ví user, kèm tham chiếu về nghiệp vụ gốc (ví dụ giao dịch thanh toán). */
    @Transactional
    public LedgerTransaction deposit(long userId, long amount, String idempotencyKey,
                                     String referenceType, Long referenceId) {
        requirePositive(amount);
        long userWalletId = userWalletId(userId);
        long externalId = systemWalletId(WalletType.PLATFORM_EXTERNAL);
        return ledgerService.post(LedgerTxType.DEPOSIT, idempotencyKey, referenceType, referenceId, "Deposit",
                List.of(new LedgerLine(externalId, BalanceType.AVAILABLE, -amount),
                        new LedgerLine(userWalletId, BalanceType.AVAILABLE, amount)));
    }

    /** Giữ nguyên chữ ký cũ cho code và test hiện có. */
    @Transactional
    public LedgerTransaction deposit(long userId, long amount, String idempotencyKey) {
        return deposit(userId, amount, idempotencyKey, "DEPOSIT", null);
    }

    /** Rút bước 1: chuyển tiền từ AVAILABLE sang HELD (R29), kèm tham chiếu về lệnh rút. */
    @Transactional
    public LedgerTransaction holdForWithdrawal(long userId, long amount, String idempotencyKey,
                                               String referenceType, Long referenceId) {
        requirePositive(amount);
        long walletId = userWalletId(userId);
        return ledgerService.post(LedgerTxType.WITHDRAW_HOLD, idempotencyKey, referenceType, referenceId,
                "Hold for withdrawal",
                List.of(new LedgerLine(walletId, BalanceType.AVAILABLE, -amount),
                        new LedgerLine(walletId, BalanceType.HELD, amount)));
    }

    @Transactional
    public LedgerTransaction holdForWithdrawal(long userId, long amount, String idempotencyKey) {
        return holdForWithdrawal(userId, amount, idempotencyKey, "WITHDRAWAL", null);
    }

    /** Rút thất bại rõ ràng: trả tiền từ HELD về AVAILABLE. */
    @Transactional
    public LedgerTransaction releaseHold(long userId, long amount, String idempotencyKey,
                                         String referenceType, Long referenceId) {
        requirePositive(amount);
        long walletId = userWalletId(userId);
        return ledgerService.post(LedgerTxType.WITHDRAW_RELEASE, idempotencyKey, referenceType, referenceId,
                "Release hold",
                List.of(new LedgerLine(walletId, BalanceType.HELD, -amount),
                        new LedgerLine(walletId, BalanceType.AVAILABLE, amount)));
    }

    @Transactional
    public LedgerTransaction releaseHold(long userId, long amount, String idempotencyKey) {
        return releaseHold(userId, amount, idempotencyKey, "WITHDRAWAL", null);
    }

    /** Rút thành công: tiền đang HELD chuyển hẳn ra bên ngoài. */
    @Transactional
    public LedgerTransaction confirmWithdrawal(long userId, long amount, String idempotencyKey,
                                               String referenceType, Long referenceId) {
        requirePositive(amount);
        long userWalletId = userWalletId(userId);
        long externalId = systemWalletId(WalletType.PLATFORM_EXTERNAL);
        return ledgerService.post(LedgerTxType.WITHDRAW_CONFIRM, idempotencyKey, referenceType, referenceId,
                "Confirm withdrawal",
                List.of(new LedgerLine(userWalletId, BalanceType.HELD, -amount),
                        new LedgerLine(externalId, BalanceType.AVAILABLE, amount)));
    }

    @Transactional
    public LedgerTransaction confirmWithdrawal(long userId, long amount, String idempotencyKey) {
        return confirmWithdrawal(userId, amount, idempotencyKey, "WITHDRAWAL", null);
    }

    /** Chuyển gốc từ ví user sang ví giữ gốc (custody) khi mở gói tiết kiệm (R13). */
    @Transactional
    public LedgerTransaction moveToCustody(long userId, long amount, String idempotencyKey,
                                           String referenceType, long referenceId) {
        requirePositive(amount);
        long userWalletId = userWalletId(userId);
        long custodyId = systemWalletId(WalletType.PLATFORM_SAVINGS_CUSTODY);
        return ledgerService.post(LedgerTxType.OPEN_SAVINGS, idempotencyKey, referenceType, referenceId,
                "Open savings account",
                List.of(new LedgerLine(userWalletId, BalanceType.AVAILABLE, -amount),
                        new LedgerLine(custodyId, BalanceType.AVAILABLE, amount)));
    }

    /** Quyết toán đáo hạn (giữ nguyên chữ ký cũ, nên MaturityService không phải đổi). */
    @Transactional
    public Optional<LedgerTransaction> transferForSettlement(long userId, SettlementTransfer transfer,
                                                             String idempotencyKey,
                                                             String referenceType, long referenceId) {
        return transferForSettlement(userId, transfer, LedgerTxType.MATURITY_SETTLEMENT,
                idempotencyKey, referenceType, referenceId);
    }

    /**
     * Chuyển tiền khi kết thúc gói tiết kiệm (đáo hạn hoặc rút sớm) trong MỘT giao dịch sổ cái (R45).
     * Trả rỗng nếu không có đồng nào di chuyển (R46).
     */
    @Transactional
    public Optional<LedgerTransaction> transferForSettlement(long userId, SettlementTransfer transfer,
                                                             LedgerTxType txType, String idempotencyKey,
                                                             String referenceType, long referenceId) {
        if (transfer.isEmpty()) {
            return Optional.empty();
        }
        long userWalletId = userWalletId(userId);
        long custodyId = systemWalletId(WalletType.PLATFORM_SAVINGS_CUSTODY);
        long poolId = systemWalletId(WalletType.PLATFORM_INTEREST_POOL);

        List<LedgerLine> lines = new ArrayList<>();
        addLine(lines, custodyId, transfer.poolToCustody() - transfer.custodyToUser());
        addLine(lines, poolId, -(transfer.poolToUser() + transfer.poolToCustody()));
        addLine(lines, userWalletId, transfer.custodyToUser() + transfer.poolToUser());

        String description = txType == LedgerTxType.EARLY_WITHDRAWAL
                ? "Savings early withdrawal" : "Savings maturity settlement";
        return Optional.of(ledgerService.post(txType, idempotencyKey,
                referenceType, referenceId, description, lines));
    }

    /** Chỉ thêm dòng khi có biến động thật (ledger cấm dòng amount = 0). */
    private static void addLine(List<LedgerLine> lines, long walletId, long delta) {
        if (delta != 0) {
            lines.add(new LedgerLine(walletId, BalanceType.AVAILABLE, delta));
        }
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

    /** Báo lỗi nếu user chưa có ví, mà KHÔNG nạp entity Wallet vào bộ nhớ đệm (quy tắc L2). */
    @Transactional(readOnly = true)
    public void requireWallet(long userId) {
        userWalletId(userId);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}