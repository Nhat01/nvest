package vn.nvest.savings.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class ApiErrorIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("URL công khai không tồn tại trả problem+json 404, không phải trang lỗi mặc định")
    void unknownPublicUrlReturnsProblemJson() throws Exception {
        mockMvc.perform(get("/webhooks/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    @DisplayName("Sai HTTP method trả problem+json 405")
    void wrongMethodReturnsProblemJson() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Method not allowed"));
    }
}
