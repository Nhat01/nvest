package vn.nvest.savings.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Biến exception thành phản hồi lỗi chuẩn RFC 7807 (application/problem+json), theo R66. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    // ---- 400: dữ liệu gửi lên sai ----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "Malformed request body");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail onMissingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "Missing required header: " + e.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "Invalid value for parameter '" + e.getName() + "'");
    }

    /** Các service ném IllegalArgumentException với thông báo do ta viết (khoảng tiền, độ dài key...). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    // ---- 401 ----

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail onInvalidCredentials(InvalidCredentialsException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", e.getMessage());
    }

    // ---- 404: không thấy, hoặc không thuộc về user (R68) ----

    @ExceptionHandler({AccountNotFoundException.class, PaymentNotFoundException.class,
            ProductNotFoundException.class, WalletNotFoundException.class})
    public ProblemDetail onNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    // ---- 409: xung đột ----

    @ExceptionHandler({EmailAlreadyUsedException.class, IdempotencyConflictException.class,
            InvalidStateTransitionException.class, ProductAlreadyExistsException.class})
    public ProblemDetail onConflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    // ---- 422: vi phạm quy tắc nghiệp vụ ----

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail onInsufficientFunds(InsufficientFundsException e) {
        // Cố tình không trả e.getMessage(): thông báo nội bộ chứa id ví và số dư
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds",
                "Available balance is not enough for this operation");
    }

    @ExceptionHandler(ProductNotAvailableException.class)
    public ProblemDetail onProductNotAvailable(ProductNotAvailableException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Product not available", e.getMessage());
    }

    // ---- 501: cổng hiện tại không hỗ trợ thao tác này ----

    @ExceptionHandler(PayoutNotSupportedException.class)
    public ProblemDetail onPayoutNotSupported(PayoutNotSupportedException e) {
        return problem(HttpStatus.NOT_IMPLEMENTED, "Withdrawals not supported", e.getMessage());
    }
}