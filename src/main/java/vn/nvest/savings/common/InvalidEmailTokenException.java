package vn.nvest.savings.common;

public class InvalidEmailTokenException extends RuntimeException {
    public InvalidEmailTokenException() {
        super("Invalid or expired token");
    }
}
