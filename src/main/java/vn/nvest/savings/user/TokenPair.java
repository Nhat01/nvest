package vn.nvest.savings.user;

public record TokenPair(TokenService.IssuedToken accessToken, String refreshToken, long refreshExpiresIn) {
}
