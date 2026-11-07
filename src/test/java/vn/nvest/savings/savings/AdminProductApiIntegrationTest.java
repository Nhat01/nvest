package vn.nvest.savings.savings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;
import vn.nvest.savings.user.Role;

class AdminProductApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken() {
        return bearer(createUserWithDeposit(0), Role.ADMIN);
    }

    private static String newCode() {
        return "T_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private ResultActions createProduct(String token, String code, int termDays, String ratePercent,
                                        long min, long max) throws Exception {
        String body = String.format(
                "{\"code\":\"%s\",\"name\":\"Test product\",\"termDays\":%d,\"annualRatePercent\":%s,"
                        + "\"minAmount\":%d,\"maxAmount\":%d}", code, termDays, ratePercent, min, max);
        var request = post("/api/admin/products").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request = request.header(AUTH, token);
        }
        return mockMvc.perform(request);
    }

    private long openOn(String userToken, String code, long amount, double expectedRatePercent) throws Exception {
        String body = mockMvc.perform(post("/api/savings").header(AUTH, userToken)
                        .header("Idempotency-Key", "open:" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productCode\":\"" + code + "\",\"amount\":" + amount
                                + ",\"maturityOption\":\"PAYOUT_ALL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.annualRatePercent").value(expectedRatePercent))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    @DisplayName("R64, R69: USER bị chặn 403, chưa đăng nhập 401, ADMIN tạo được sản phẩm")
    void rolesAreEnforced() throws Exception {
        String userToken = bearer(createUserWithDeposit(0));

        createProduct(userToken, newCode(), 30, "5.00", 1_000_000, 500_000_000).andExpect(status().isForbidden());
        createProduct(null, newCode(), 30, "5.00", 1_000_000, 500_000_000).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/products").header(AUTH, userToken)).andExpect(status().isForbidden());

        createProduct(adminToken(), newCode(), 30, "5.00", 1_000_000, 500_000_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(jsonPath("$.annualRatePercent").value(5.0))
                .andExpect(jsonPath("$.termDays").value(30));
    }

    @Test
    @DisplayName("R36, R69: dữ liệu sai bị chặn ngay ở API (mã, lãi suất, số chữ số thập phân, khoảng tiền, kỳ hạn)")
    void invalidProductsAreRejected() throws Exception {
        String admin = adminToken();

        createProduct(admin, "bad code", 30, "5.00", 1_000_000, 500_000_000).andExpect(status().isBadRequest());
        createProduct(admin, newCode(), 30, "25.00", 1_000_000, 500_000_000).andExpect(status().isBadRequest());   // > 20%
        createProduct(admin, newCode(), 30, "-1.00", 1_000_000, 500_000_000).andExpect(status().isBadRequest());
        createProduct(admin, newCode(), 30, "6.255", 1_000_000, 500_000_000).andExpect(status().isBadRequest());  // 3 chữ số
        createProduct(admin, newCode(), 30, "5.00", 2_000_000, 1_000_000).andExpect(status().isBadRequest());     // max < min
        createProduct(admin, newCode(), 0, "5.00", 1_000_000, 500_000_000).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Mã sản phẩm trùng trả 409")
    void duplicateCodeIsAConflict() throws Exception {
        String admin = adminToken();
        String code = newCode();
        createProduct(admin, code, 30, "5.00", 1_000_000, 500_000_000).andExpect(status().isCreated());

        createProduct(admin, code, 60, "6.00", 1_000_000, 500_000_000).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("R5, R69: đổi lãi suất không ảnh hưởng gói đã mở, chỉ áp dụng cho gói mở sau đó")
    void rateChangeAppliesOnlyToNewAccounts() throws Exception {
        String admin = adminToken();
        String code = newCode();
        createProduct(admin, code, 30, "5.00", 1_000_000, 500_000_000).andExpect(status().isCreated());
        String userToken = bearer(createUserWithDeposit(4_000_000));

        long first = openOn(userToken, code, 2_000_000, 5.0);
        mockMvc.perform(put("/api/admin/products/" + code + "/rate").header(AUTH, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"annualRatePercent\":7.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualRatePercent").value(7.0));
        long second = openOn(userToken, code, 2_000_000, 7.0);

        mockMvc.perform(get("/api/savings/" + first).header(AUTH, userToken))
                .andExpect(jsonPath("$.annualRatePercent").value(5.0));   // giữ lãi suất đã chốt
        mockMvc.perform(get("/api/savings/" + second).header(AUTH, userToken))
                .andExpect(jsonPath("$.annualRatePercent").value(7.0));
    }

    @Test
    @DisplayName("R36: đổi sang lãi suất vượt 20% hoặc sản phẩm lạ bị từ chối")
    void invalidRateChangeIsRejected() throws Exception {
        String admin = adminToken();
        String code = newCode();
        createProduct(admin, code, 30, "5.00", 1_000_000, 500_000_000).andExpect(status().isCreated());

        mockMvc.perform(put("/api/admin/products/" + code + "/rate").header(AUTH, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"annualRatePercent\":30.00}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/products/NO_SUCH_CODE/rate").header(AUTH, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"annualRatePercent\":5.00}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/products/NO_SUCH_CODE/stop").header(AUTH, admin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("R69: ngừng bán thì biến khỏi danh sách của user, không mở mới được, admin vẫn thấy")
    void stoppedProductDisappearsFromTheShop() throws Exception {
        String admin = adminToken();
        String code = newCode();
        createProduct(admin, code, 30, "5.00", 1_000_000, 500_000_000).andExpect(status().isCreated());
        String userToken = bearer(createUserWithDeposit(2_000_000));
        String listedFilter = "$[?(@.code == '" + code + "')]";

        mockMvc.perform(get("/api/savings/products").header(AUTH, userToken))
                .andExpect(jsonPath(listedFilter + ".termDays").value(30));

        mockMvc.perform(post("/api/admin/products/" + code + "/stop").header(AUTH, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        mockMvc.perform(get("/api/savings/products").header(AUTH, userToken))
                .andExpect(jsonPath(listedFilter).isEmpty());
        mockMvc.perform(post("/api/savings").header(AUTH, userToken)
                        .header("Idempotency-Key", "open:" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productCode\":\"" + code + "\",\"amount\":1000000,\"maturityOption\":\"PAYOUT_ALL\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/admin/products").header(AUTH, admin))
                .andExpect(jsonPath(listedFilter + ".status").value("STOPPED"));
    }
}