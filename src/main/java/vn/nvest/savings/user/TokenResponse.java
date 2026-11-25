package vn.nvest.savings.user;

public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                            String refreshToken, long refreshExpiresIn) {

    public static TokenResponse of(TokenService.IssuedToken token, String refreshToken, long refreshExpiresIn) {
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), refreshToken, refreshExpiresIn);
    }
}
