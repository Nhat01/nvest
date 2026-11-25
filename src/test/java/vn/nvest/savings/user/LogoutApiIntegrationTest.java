package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

/**
 * Test HTTP cho T6: POST /api/auth/logout, gồm thu hồi một phiên và mọi thiết bị.
 */
class LogoutApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "S3cure-passw0rd";

    @Autowired
    private MockMvc mockMvc;

    private static String uniqueEmail() {
        return "logout-" + UUID.randomUUID() + "@test.local";
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"fullName\":\"Logout Test\"}"))
                .andExpect(status().isCreated());
    }

    private String loginAndGetRefreshToken(String email) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(json, "$.refreshToken");
    }

    private ResultActions refresh(String rawToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rawToken + "\"}"));
    }

    private ResultActions logout(String rawToken, Boolean allDevices) throws Exception {
        String body = allDevices == null
                ? "{\"refreshToken\":\"" + rawToken + "\"}"
                : "{\"refreshToken\":\"" + rawToken + "\",\"allDevices\":" + allDevices + "}";
        return mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String revokedReasonOf(String rawToken) {
        return jdbc.queryForObject("SELECT revoked_reason FROM refresh_tokens WHERE token_hash = ?",
                String.class, sha256Hex(rawToken));
    }

    @Test
    @DisplayName("T6: logout chỉ thu hồi phiên hiện tại, phiên khác vẫn dùng được")
    void logoutRevokesOnlyCurrentSession() throws Exception {
        String email = uniqueEmail();
        register(email);
        String sessionA = loginAndGetRefreshToken(email);
        String sessionB = loginAndGetRefreshToken(email);

        // Không gửi allDevices -> mặc định false
        logout(sessionA, null).andExpect(status().isNoContent());

        assertThat(revokedReasonOf(sessionA)).isEqualTo(RevokedReason.LOGOUT.name());
        assertThat(revokedReasonOf(sessionB)).isNull();

        refresh(sessionA).andExpect(status().isUnauthorized());
        refresh(sessionB).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T6: logout lần hai vẫn 204 (idempotent)")
    void logoutIsIdempotent() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginAndGetRefreshToken(email);

        logout(token, false).andExpect(status().isNoContent());
        logout(token, false).andExpect(status().isNoContent());

        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("T6: allDevices=true thu hồi mọi phiên của user")
    void logoutAllDevicesRevokesEverySession() throws Exception {
        String email = uniqueEmail();
        register(email);
        String first = loginAndGetRefreshToken(email);
        String second = loginAndGetRefreshToken(email);
        String third = loginAndGetRefreshToken(email);

        logout(first, true).andExpect(status().isNoContent());

        for (String token : java.util.List.of(first, second, third)) {
            assertThat(revokedReasonOf(token)).isEqualTo(RevokedReason.LOGOUT_ALL.name());
            refresh(token).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("T6: allDevices chỉ ảnh hưởng user sở hữu token, không đụng user khác")
    void logoutAllDevicesDoesNotTouchOtherUsers() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        register(emailA);
        register(emailB);
        String tokenA = loginAndGetRefreshToken(emailA);
        String tokenB = loginAndGetRefreshToken(emailB);

        logout(tokenA, true).andExpect(status().isNoContent());

        refresh(tokenA).andExpect(status().isUnauthorized());
        refresh(tokenB).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T6: token không tồn tại vẫn trả 204, không lộ trạng thái")
    void logoutWithUnknownTokenIsNoContent() throws Exception {
        logout("not-a-real-token", false).andExpect(status().isNoContent());
        logout("not-a-real-token", true).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("T6: thiếu hoặc rỗng refreshToken trả 400")
    void logoutWithoutRefreshTokenIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.refreshToken").exists());

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
}
