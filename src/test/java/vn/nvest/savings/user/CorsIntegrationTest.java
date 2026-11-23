package vn.nvest.savings.user;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class CorsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Preflight từ origin dev được chấp nhận, không cần token")
    void preflightFromAllowedOriginIsAccepted() throws Exception {
        mockMvc.perform(options("/api/wallet")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString("Authorization")));
    }

    @Test
    @DisplayName("Origin lạ bị từ chối preflight")
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/wallet")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Response thật từ origin được phép có Access-Control-Allow-Origin")
    void actualRequestFromAllowedOriginGetsCorsHeader() throws Exception {
        long userId = createUserWithDeposit(0);

        mockMvc.perform(get("/api/wallet")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:5173"));
    }
}
