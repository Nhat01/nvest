package vn.nvest.savings.user;

public interface EmailSender {
    void sendVerification(String to, String fullName, String rawToken);
    void sendPasswordReset(String to, String fullName, String rawToken);
}
