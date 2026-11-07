package vn.nvest.savings.user;

/** Thông tin trả về cho client. Cố tình KHÔNG có passwordHash. */
public record UserProfile(Long id, String email, String fullName, Role role) {

    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}