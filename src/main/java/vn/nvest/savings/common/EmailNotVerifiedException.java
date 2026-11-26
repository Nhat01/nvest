package vn.nvest.savings.common;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Email has not been verified");
    }
}
