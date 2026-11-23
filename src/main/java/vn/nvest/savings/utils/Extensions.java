package vn.nvest.savings.utils;

/** Tiện ích nhỏ dùng chung. Chỉ giữ hàm thực sự được gọi để tránh dead code. */
public final class Extensions {

    private Extensions() {
    }

    public static boolean isBlankOrNull(String str) {
        return str == null || str.isBlank();
    }
}
