package vn.nvest.savings.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.WalletNotFoundException;
import vn.nvest.savings.utils.Extensions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LedgerService {

    private final WalletRepository walletRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerService(WalletRepository walletRepository,
                         LedgerTransactionRepository transactionRepository,
                         LedgerEntryRepository entryRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    /**
     * Ghi một giao dịch sổ cái. Tham gia transaction của người gọi nếu có.
     * Gọi lại với cùng idempotencyKey thì trả giao dịch cũ, không ghi lần hai (R35).
     */
    @Transactional
    public LedgerTransaction post(LedgerTxType txType, String idempotencyKey,
                                  String referenceType, Long referenceId,
                                  String description, List<LedgerLine> lines) {
        validate(idempotencyKey, lines);

        // 1) Khóa mọi ví liên quan, theo thứ tự id tăng dần (tránh deadlock)
        Map<Long, Wallet> wallets = lockWallets(lines);

        // 2) Kiểm tra trùng SAU khi đã giữ khóa
        Optional<LedgerTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().getTxType() != txType) {
                throw new IdempotencyConflictException("Key '" + idempotencyKey
                        + "' was already used for " + existing.get().getTxType());
            }
            return existing.get();
        }

        // 3) Ghi giao dịch, ghi từng dòng, cập nhật số dư
        LedgerTransaction tx = transactionRepository.save(
                new LedgerTransaction(txType, idempotencyKey, referenceType, referenceId, description));
        for (LedgerLine line : lines) {
            wallets.get(line.walletId()).apply(line.balanceType(), line.amount());
            entryRepository.save(new LedgerEntry(
                    tx.getId(), line.walletId(), line.balanceType(), line.amount()));
        }
        return tx;
    }

    private Map<Long, Wallet> lockWallets(List<LedgerLine> lines) {
        List<Long> sortedIds = lines.stream().map(LedgerLine::walletId).distinct().sorted().toList();
        Map<Long, Wallet> locked = new LinkedHashMap<>();
        for (Long id : sortedIds) {
            Wallet wallet = walletRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + id));
            locked.put(id, wallet);
        }
        return locked;
    }

    static void validate(String idempotencyKey, List<LedgerLine> lines) {
        if (Extensions.isBlankOrNull(idempotencyKey) || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("idempotencyKey is required, max 100 chars (R35)");
        }
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction needs at least 2 lines");
        }
        long sum = 0;
        for (LedgerLine line : lines) {
            if (line.amount() == 0) {
                throw new IllegalArgumentException("A ledger line must not have amount 0");
            }
            sum = Math.addExact(sum, line.amount());
        }
        if (sum != 0) {
            throw new IllegalArgumentException("Ledger lines must sum to 0 (R37), got " + sum);
        }
    }

    /** Giới hạn số dòng export để một request không kéo cả sổ cái vào bộ nhớ. */
    public static final int MAX_EXPORT_ROWS = 10_000;

    @Transactional(readOnly = true)
    public TransactionHistoryPage getStatement(long walletId, TransactionFilter filter, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);   // R68: tối đa 100 dòng mỗi trang
        Page<StatementLine> result = entryRepository.searchStatement(
                walletId, filter, PageRequest.of(safePage, safeSize));
        return TransactionHistoryPage.from(result);
    }

    /** Export tối đa MAX_EXPORT_ROWS dòng; lấy dư 1 dòng để biết dữ liệu có bị cắt hay không. */
    @Transactional(readOnly = true)
    public TransactionExport exportStatement(long walletId, TransactionFilter filter) {
        List<StatementLine> rows = entryRepository.searchStatement(
                walletId, filter, MAX_EXPORT_ROWS + 1);
        boolean truncated = rows.size() > MAX_EXPORT_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_EXPORT_ROWS);
        }
        return new TransactionExport(rows, truncated);
    }
}
