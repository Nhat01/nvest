package vn.nvest.savings.savings;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Chỉ ADMIN truy cập được: luật nằm ở SecurityConfig (/api/admin/** yêu cầu ROLE_ADMIN). */
@RestController
@RequestMapping("/api/admin/products")
@Tag(name = "Admin", description = "Quản lý sản phẩm tiết kiệm (chỉ ADMIN)")
public class AdminProductController {

    private final AdminProductService adminProductService;

    public AdminProductController(AdminProductService adminProductService) {
        this.adminProductService = adminProductService;
    }

    @GetMapping
    public List<AdminProductResponse> list() {
        return adminProductService.listAll().stream().map(AdminProductResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return AdminProductResponse.from(adminProductService.create(request.code(), request.name(),
                request.termDays(), request.annualRatePercent(), request.minAmount(), request.maxAmount()));
    }

    @PutMapping("/{code}/rate")
    public AdminProductResponse changeRate(@PathVariable String code,
                                           @Valid @RequestBody ChangeRateRequest request) {
        return AdminProductResponse.from(adminProductService.changeRate(code, request.annualRatePercent()));
    }

    @PostMapping("/{code}/stop")
    public AdminProductResponse stopSale(@PathVariable String code) {
        return AdminProductResponse.from(adminProductService.stopSale(code));
    }
}