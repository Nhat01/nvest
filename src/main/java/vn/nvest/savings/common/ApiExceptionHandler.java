package vn.nvest.savings.common;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Biến exception thành phản hồi lỗi chuẩn RFC 7807 (application/problem+json), theo R66. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail onMissingParameter(MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "Missing required parameter: " + e.getParameterName());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail onHandlerMethodValidation(HandlerMethodValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onConstraintViolation(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail onUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
                "Content type is not supported for this endpoint");
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

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ProblemDetail> onTooManyLoginAttempts(TooManyLoginAttemptsException e) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail onInvalidRefreshToken(InvalidRefreshTokenException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", e.getMessage());
    }


    // ---- 403: đã xác thực nhưng không đủ quyền ----

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "Access is denied");
    }

    // ---- 404: không thấy, hoặc không thuộc về user (R68) ----

    @ExceptionHandler({AccountNotFoundException.class, PaymentNotFoundException.class,
            ProductNotFoundException.class, WalletNotFoundException.class})
    public ProblemDetail onNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    /** URL không khớp controller/static resource nào. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail onUnknownUrl(Exception e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "The requested resource does not exist");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail onMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                "HTTP method " + e.getMethod() + " is not supported for this endpoint");
    }

    // ---- 409: xung đột ----

    @ExceptionHandler({EmailAlreadyUsedException.class, IdempotencyConflictException.class,
            InvalidStateTransitionException.class, ProductAlreadyExistsException.class})
    public ProblemDetail onConflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    /** Ràng buộc DB bị vi phạm ở tầng không lường trước (unique, foreign key...). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Database constraint violation", e);
        return problem(HttpStatus.CONFLICT, "Conflict", "The request conflicts with existing data");
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

    // ---- Các lỗi còn lại ----

    /** Giữ nguyên status/title/detail của exception đã tự mô tả thành ProblemDetail. */
    @ExceptionHandler(ErrorResponseException.class)
    public ProblemDetail onErrorResponse(ErrorResponseException e) {
        return e.getBody();
    }

    /**
     * Lưới an toàn cuối cùng: không để lộ stack trace hay thông điệp nội bộ ra client.
     * Chi tiết được ghi log để còn điều tra.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred");
    }
}