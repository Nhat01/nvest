package vn.nvest.savings.payment;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.PaymentNotFoundException;

/** Truy vấn giao dịch thanh toán CỦA CHÍNH user (R68). */
@Service
public class PaymentQueryService {

    private final PaymentTransactionRepository paymentRepository;

    public PaymentQueryService(PaymentTransactionRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> list(long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return paymentRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(safePage, safeSize));
    }

    /** Giao dịch của người khác trả "không thấy", không phải "không có quyền" (R68). */
    @Transactional(readOnly = true)
    public PaymentTransaction get(long userId, long paymentId) {
        return paymentRepository.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
    }
}