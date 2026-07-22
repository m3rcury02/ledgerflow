package com.ledgerflow;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "ledgerflow.api-docs.enabled=true")
@AutoConfigureMockMvc
class ApiDocsEnabledIntegrationTest extends PostgreSqlIntegrationTest {

  private static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
          + "base-uri 'none'; form-action 'none'";

  @Autowired MockMvc mockMvc;

  @Test
  void servesThePackagedContractAndUiAssetsOnlyWhenEnabled() throws Exception {
    mockMvc
        .perform(get("/docs"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString("LedgerFlow API docs")))
        .andExpect(content().string(containsString("/docs/swagger-initializer.js")))
        .andExpect(header().string("Content-Security-Policy", CONTENT_SECURITY_POLICY))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"));

    mockMvc
        .perform(get("/docs/swagger-initializer.js"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/javascript"))
        .andExpect(content().string(containsString("/openapi/ledgerflow.yaml")));

    mockMvc
        .perform(get("/openapi/ledgerflow.yaml"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/yaml"))
        .andExpect(content().string(containsString("openapi: 3.1.0")))
        .andExpect(content().string(containsString("/api/v1/orders:")));

    mockMvc
        .perform(get("/webjars/swagger-ui/5.25.3/swagger-ui.css"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/css"));
  }

  @Test
  void apiDocsSecurityChainDoesNotPermitApplicationWrites() throws Exception {
    mockMvc
        .perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
