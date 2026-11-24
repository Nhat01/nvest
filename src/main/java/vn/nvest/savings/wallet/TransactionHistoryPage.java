package vn.nvest.savings.wallet;

import java.util.List;
import org.springframework.data.domain.Page;

/** Một trang lịch sử giao dịch, kèm metadata để frontend dựng nút phân trang. */
public record TransactionHistoryPage(
        List<StatementLine> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public static TransactionHistoryPage from(Page<StatementLine> page) {
        return new TransactionHistoryPage(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious());
    }
}
