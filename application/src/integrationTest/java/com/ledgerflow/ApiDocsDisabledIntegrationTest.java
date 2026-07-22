package com.ledgerflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ApiDocsDisabledIntegrationTest extends PostgreSqlIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void keepsEveryApiDocumentationRouteUnavailableByDefault() throws Exception {
    mockMvc.perform(get("/docs")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/docs/swagger-initializer.js")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/openapi/ledgerflow.yaml")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/webjars/swagger-ui/5.25.3/swagger-ui.css"))
        .andExpect(status().isUnauthorized());
  }
}
