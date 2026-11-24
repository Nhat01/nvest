package vn.nvest.savings.wallet;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import vn.nvest.savings.common.ClockConfig;

public class StatementSearchRepositoryImpl implements StatementSearchRepository {

    private static final String SELECT = "select new vn.nvest.savings.wallet.StatementLine("
            + "e.id, t.txType, e.balanceType, e.amount, t.referenceType, t.referenceId, "
            + "t.description, t.createdAt) "
            + "from LedgerEntry e join LedgerTransaction t on t.id = e.transactionId ";

    private static final String COUNT = "select count(e) "
            + "from LedgerEntry e join LedgerTransaction t on t.id = e.transactionId ";

    /** Ký tự escape cho LIKE, tránh user gõ '%' hay '_' làm thay đổi nghĩa tìm kiếm. */
    private static final char LIKE_ESCAPE = '!';

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<StatementLine> searchStatement(long walletId, TransactionFilter filter, Pageable pageable) {
        Query query = entityManager.createQuery(
                SELECT + whereClause(filter) + " order by e.id desc", StatementLine.class);
        bind(query, walletId, filter);
        query.setFirstResult((int) Math.min(pageable.getOffset(), Integer.MAX_VALUE));
        query.setMaxResults(pageable.getPageSize());
        List<StatementLine> content = query.getResultList();

        Query countQuery = entityManager.createQuery(COUNT + whereClause(filter));
        bind(countQuery, walletId, filter);
        long total = (Long) countQuery.getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public List<StatementLine> searchStatement(long walletId, TransactionFilter filter, int maxRows) {
        Query query = entityManager.createQuery(
                SELECT + whereClause(filter) + " order by e.id desc", StatementLine.class);
        bind(query, walletId, filter);
        query.setMaxResults(maxRows);
        return query.getResultList();
    }

    /** Ghép các điều kiện đang được dùng; tham số null thì bỏ hẳn điều kiện đó. */
    private static String whereClause(TransactionFilter filter) {
        StringBuilder where = new StringBuilder("where e.walletId = :walletId");
        if (filter.txType() != null) {
            where.append(" and t.txType = :txType");
        }
        if (filter.balanceType() != null) {
            where.append(" and e.balanceType = :balanceType");
        }
        if (filter.creditOnly()) {
            where.append(" and e.amount > 0");
        } else if (filter.debitOnly()) {
            where.append(" and e.amount < 0");
        }
        if (filter.from() != null) {
            where.append(" and t.createdAt >= :fromInstant");
        }
        if (filter.to() != null) {
            where.append(" and t.createdAt < :toExclusiveInstant");
        }
        if (filter.minAmount() != null) {
            where.append(" and abs(e.amount) >= :minAmount");
        }
        if (filter.maxAmount() != null) {
            where.append(" and abs(e.amount) <= :maxAmount");
        }
        if (filter.keyword() != null) {
            where.append(" and lower(t.description) like :keyword escape '").append(LIKE_ESCAPE).append('\'');
        }
        return where.toString();
    }

    private static void bind(Query query, long walletId, TransactionFilter filter) {
        query.setParameter("walletId", walletId);
        if (filter.txType() != null) {
            query.setParameter("txType", filter.txType());
        }
        if (filter.balanceType() != null) {
            query.setParameter("balanceType", filter.balanceType());
        }
        if (filter.from() != null) {
            query.setParameter("fromInstant", startOfDay(filter.from()));
        }
        if (filter.to() != null) {
            query.setParameter("toExclusiveInstant", startOfDay(filter.to().plusDays(1)));
        }
        if (filter.minAmount() != null) {
            query.setParameter("minAmount", filter.minAmount());
        }
        if (filter.maxAmount() != null) {
            query.setParameter("maxAmount", filter.maxAmount());
        }
        if (filter.keyword() != null) {
            query.setParameter("keyword", "%" + escapeLike(filter.keyword().toLowerCase(Locale.ROOT)) + "%");
        }
    }

    /** Ngày lọc là ngày nghiệp vụ Việt Nam; 'to' bao gồm cả ngày đó nên chặn ở đầu ngày kế tiếp. */
    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ClockConfig.BUSINESS_ZONE).toInstant();
    }

    static String escapeLike(String value) {
        String escape = String.valueOf(LIKE_ESCAPE);
        return value
                .replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }
}
