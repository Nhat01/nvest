package vn.nvest.savings.user;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static TokenResponse of(TokenService.IssuedToken token) {
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }
}