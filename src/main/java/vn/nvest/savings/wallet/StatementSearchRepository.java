package vn.nvest.savings.wallet;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Truy vấn sao kê có bộ lọc tuỳ chọn.
 *
 * Dùng custom fragment thay vì một câu JPQL cố định vì bộ lọc nào cũng có thể null.
 * Cách này chỉ thêm điều kiện và tham số khi thực sự cần, tránh lỗi PostgreSQL
 * "could not determine data type of parameter" khi so sánh tham số null với enum.
 */
public interface StatementSearchRepository {

    Page<StatementLine> searchStatement(long walletId, TransactionFilter filter, Pageable pageable);

    /** Lấy tối đa maxRows dòng, dùng cho export. */
    List<StatementLine> searchStatement(long walletId, TransactionFilter filter, int maxRows);
}
