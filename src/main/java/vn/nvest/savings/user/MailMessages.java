package vn.nvest.savings.user;

// Nội dung email dùng chung cho mọi EmailSender (SMTP, Brevo, logging).
// Tách ra để subject/body chỉ tồn tại ở một nơi.
final class MailMessages {

    record Content(String subject, String body) {
    }

    private MailMessages() {
    }

    static Content verification(MailProperties properties, String fullName, String rawToken) {
        return new Content("Xác thực email của bạn", """
                Xin chào %s,

                Hãy mở link sau để xác thực email:
                %s%s

                Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.
                """.formatted(fullName, properties.verificationLink(), rawToken));
    }

    static Content passwordReset(MailProperties properties, String fullName, String rawToken) {
        return new Content("Đặt lại mật khẩu", """
                Xin chào %s,

                Hãy mở link sau để đặt lại mật khẩu:
                %s%s

                Nếu bạn không yêu cầu, hãy bỏ qua email này. Mật khẩu hiện tại vẫn dùng được.
                """.formatted(fullName, properties.resetLink(), rawToken));
    }

    static Content maturityReminder(String fullName, long accountId, String message) {
        return new Content("Nhắc đáo hạn gói tiết kiệm #" + accountId, """
                Xin chào %s,

                %s

                Trân trọng,
                Nvest Savings Platform
                """.formatted(fullName, message));
    }
}
