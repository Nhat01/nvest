package vn.nvest.savings.common;

import org.springframework.security.oauth2.jwt.Jwt;

/** Lấy id người dùng từ token đã được xác minh. Nguồn DUY NHẤT của userId trong các API (R64). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}