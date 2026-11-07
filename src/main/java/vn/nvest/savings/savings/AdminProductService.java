package vn.nvest.savings.savings;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.ProductAlreadyExistsException;
import vn.nvest.savings.common.ProductNotFoundException;

@Service
public class AdminProductService {

    private final SavingsProductRepository productRepository;

    public AdminProductService(SavingsProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<SavingsProduct> listAll() {
        return productRepository.findAll(Sort.by("termDays", "code"));
    }

    @Transactional
    public SavingsProduct create(String code, String name, int termDays, BigDecimal annualRatePercent,
                                 long minAmount, long maxAmount) {
        if (productRepository.findByCode(code).isPresent()) {
            throw new ProductAlreadyExistsException("Product already exists: " + code);
        }
        try {
            // movePointLeft dịch dấu phẩy chính xác: 6.25 (phần trăm) -> 0.0625 (thập phân)
            return productRepository.saveAndFlush(new SavingsProduct(code, name, termDays,
                    annualRatePercent.movePointLeft(2), minAmount, maxAmount));
        } catch (DataIntegrityViolationException e) {
            throw new ProductAlreadyExistsException("Product already exists: " + code);   // hai admin tạo cùng lúc
        }
    }

    /** R69: chỉ áp dụng cho gói mở sau đó và các kỳ tái tục; gói đã mở giữ lãi suất đã chốt (R5). */
    @Transactional
    public SavingsProduct changeRate(String code, BigDecimal annualRatePercent) {
        SavingsProduct product = requireProduct(code);
        product.changeAnnualRate(annualRatePercent.movePointLeft(2));
        return product;
    }

    @Transactional
    public SavingsProduct stopSale(String code) {
        SavingsProduct product = requireProduct(code);
        product.stopSale();
        return product;
    }

    private SavingsProduct requireProduct(String code) {
        return productRepository.findByCode(code)
                .orElseThrow(() -> new ProductNotFoundException("Unknown product: " + code));
    }
}