package vn.nvest.savings.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class OpenApiDisabledIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Khi tắt OpenAPI, cả /v3/api-docs và Swagger UI đều không tồn tại")
    void docsAreNotPublishedWhenDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
