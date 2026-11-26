package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.payment.GatewayStatus;
import vn.nvest.savings.payment.MockPaymentGateway;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;
import vn.nvest.savings.wallet.WalletService;

class AuthIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "S3cure-passw0rd";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockPaymentGateway gateway;

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }

    private static String json(String email, String password, String fullName) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"" + fullName + "\"}";
    }

    private ResultActions register(String email, String password, String fullName) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(json(email, password, fullName)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions me(String authorizationHeader) throws Exception {
        var request = get("/api/me");
        if (authorizationHeader != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return mockMvc.perform(request);
    }

    private long registerAndGetId(String email) throws Exception {
        String body = register(email, PASSWORD, "Nguyen Van An").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Test không đi qua hộp thư: đánh dấu xác thực để login được.
        markEmailVerified(email);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String loginAndGetToken(String email) throws Exception {
        String body = login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    @DisplayName("R61: đăng ký tạo user (email chuẩn hóa chữ thường) và ví; mật khẩu chỉ lưu dạng băm")
    void registerCreatesUserAndWallet() throws Exception {
        String email = uniqueEmail().toUpperCase(Locale.ROOT);

        String body = register(email, PASSWORD, "Nguyễn Văn An")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long userId = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(walletService.getBalance(userId)).isEqualTo(new WalletService.WalletBalance(0, 0));
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("R61: email trùng (không phân biệt hoa thường) trả 409")
    void duplicateEmailIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD, "An").andExpect(status().isCreated());

        register(email.toUpperCase(Locale.ROOT), PASSWORD, "Binh")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("Dữ liệu sai trả 400 kèm lỗi từng trường")
    void invalidInputIsRejectedWithFieldErrors() throws Exception {
        register("not-an-email", "short", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.fullName").exists());
    }

    @Test
    @DisplayName("R61: mật khẩu ngắn hơn 72 ký tự nhưng dài hơn 72 BYTE bị từ chối")
    void passwordIsLimitedInBytesNotCharacters() throws Exception {
        String thirtyThreeByteChars = "ệ".repeat(30);   // 30 ký tự nhưng 90 byte

        register(uniqueEmail(), thirtyThreeByteChars, "An")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    @DisplayName("R62: đăng nhập đúng nhận token Bearer sống 3600 giây")
    void loginReturnsABearerToken() throws Exception {
        String email = uniqueEmail();
        registerAndGetId(email);

        login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    @DisplayName("R63: sai mật khẩu và email không tồn tại cho CÙNG một phản hồi 401")
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        String email = uniqueEmail();
        registerAndGetId(email);

        String wrongPassword = login(email, "wrong-password-1")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail(), PASSWORD)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    @Test
    @DisplayName("R63: tài khoản bị khóa không đăng nhập được, cũng chỉ thấy lỗi chung")
    void lockedUserCannotLogIn() throws Exception {
        String email = uniqueEmail();
        long userId = registerAndGetId(email);
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", userId);

        login(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R64: /api/me cần token hợp lệ và trả thông tin của chính chủ token")
    void meRequiresAValidToken() throws Exception {
        String email = uniqueEmail();
        registerAndGetId(email);
        String token = loginAndGetToken(email);

        me(null).andExpect(status().isUnauthorized());
        me("Bearer not.a.jwt").andExpect(status().isUnauthorized());
        me("Bearer " + token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("R64: token giả mạo (khóa khác, hết hạn, sửa payload) đều bị từ chối")
    void forgedAndExpiredTokensAreRejected() throws Exception {
        String email = uniqueEmail();
        long userId = registerAndGetId(email);
        String realToken = loginAndGetToken(email);

        // 1) Ký bằng khóa khác, tự phong ADMIN
        String wrongKey = new TokenService("a-completely-different-secret-of-32-plus-bytes!",
                Duration.ofHours(1), Clock.systemUTC()).issue(userId, email, Role.ADMIN).value();
        // 2) Đúng khóa nhưng đã hết hạn (giả định ứng dụng đang dùng khóa mặc định của dev)
        Clock threeHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC);
        String expired = new TokenService(TokenService.DEV_DEFAULT_SECRET, Duration.ofHours(1), threeHoursAgo)
                .issue(userId, email, Role.USER).value();
        // 3) Token thật nhưng sửa payload thành ADMIN, giữ chữ ký cũ
        String[] parts = realToken.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\",\"role\":\"ADMIN\"}").getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        me("Bearer " + wrongKey).andExpect(status().isUnauthorized());
        me("Bearer " + expired).andExpect(status().isUnauthorized());
        me("Bearer " + tampered).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R64: webhook và health công khai; mọi đường khác mặc định cần đăng nhập")
    void publicAndProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        // Webhook không cần token: tới được controller (đơn không tồn tại -> 404), không bị chặn ở bộ lọc bảo mật
        MockPaymentGateway.SignedWebhook unknownOrder = gateway.webhookFor("DEP-NOPE", GatewayStatus.SUCCEEDED, 1_000);
        mockMvc.perform(post("/webhooks/mock").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", unknownOrder.signature()).content(unknownOrder.payload()))
                .andExpect(status().isNotFound());

        // Đường chưa tồn tại dưới /api vẫn yêu cầu đăng nhập trước (mặc định đóng)
        mockMvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R76: mật khẩu đúng nhưng email chưa xác thực trả 403 kèm code EMAIL_NOT_VERIFIED")
    void loginBeforeVerificationReturns403() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD, "Nguyen Van An").andExpect(status().isCreated());

        login(email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("R63/R76: sai mật khẩu khi email chưa xác thực vẫn 401, không lộ trạng thái xác thực")
    void wrongPasswordBeforeVerificationStillReturns401() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD, "Nguyen Van An").andExpect(status().isCreated());

        login(email, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("R76: xác thực email xong đăng nhập được")
    void loginAfterVerificationSucceeds() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD, "Nguyen Van An").andExpect(status().isCreated());
        markEmailVerified(email);

        login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}