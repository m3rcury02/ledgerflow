package com.ledgerflow.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class OrderHttpIntegrationTest extends PostgreSqlIntegrationTest {

  private static final String VALID_REQUEST =
      """
      {
        "clientReference": "checkout-http-1",
        "amount": {"amountMinor": 259900, "currency": "INR"}
      }
      """;

  @Autowired MockMvc mockMvc;

  @Test
  void createsAndReplaysTheOriginalResponse() throws Exception {
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(writeJwt("customer-1"))
                    .header("Idempotency-Key", "http-order-key-0001")
                    .header("X-Correlation-Id", "http-create-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "http-create-1"))
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.amount.amountMinor").value(259900))
            .andExpect(jsonPath("$.amount.currency").value("INR"))
            .andReturn();

    MvcResult replayed =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(writeJwt("customer-1"))
                    .header("Idempotency-Key", "http-order-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "amount": {"currency": "INR", "amountMinor": 259900},
                          "clientReference": "checkout-http-1"
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(header().string("Location", created.getResponse().getHeader("Location")))
            .andReturn();

    assertThat(replayed.getResponse().getContentAsString())
        .isEqualTo(created.getResponse().getContentAsString());
    assertThat(jdbcClient.sql("SELECT count(*) FROM orders").query(Long.class).single()).isOne();
  }

  @Test
  void rejectsKeyReuseWithADifferentPayload() throws Exception {
    create("customer-1", "http-conflict-key-0001", VALID_REQUEST);

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(writeJwt("customer-1"))
                .header("Idempotency-Key", "http-conflict-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("259900", "259901")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_key_reused"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void validatesTheRequiredIdempotencyKeyAndRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(writeJwt("customer-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_request"));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(writeJwt("customer-1"))
                .header("Idempotency-Key", "bad key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(writeJwt("customer-1"))
                .header("Idempotency-Key", "http-usd-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("INR", "USD")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("unsupported_currency"));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(writeJwt("customer-1"))
                .header("Idempotency-Key", "http-unknown-field-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "amount": {"amountMinor": 100, "currency": "INR"},
                      "unexpected": true
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_request"));
  }

  @Test
  void returnsOnlyAnOrderOwnedByTheAuthenticatedSubject() throws Exception {
    MvcResult created = create("customer-1", "http-read-key-0001", VALID_REQUEST);
    String orderId = JsonPath.read(created.getResponse().getContentAsString(), "$.orderId");

    mockMvc
        .perform(get("/api/v1/orders/{orderId}", orderId).with(readJwt("customer-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(orderId));

    mockMvc
        .perform(get("/api/v1/orders/{orderId}", orderId).with(readJwt("customer-2")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource_not_found"));

    mockMvc
        .perform(get("/api/v1/orders/{orderId}", UUID.randomUUID()).with(readJwt("customer-1")))
        .andExpect(status().isNotFound());
  }

  @Test
  void requiresAuthenticationAndTheCorrectScope() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "http-auth-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"))
        .andExpect(header().exists("X-Correlation-Id"));

    mockMvc
        .perform(get("/api/v1/orders/{orderId}", UUID.randomUUID()).with(writeJwt("customer-1")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("forbidden"));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(
                    jwt()
                        .jwt(token -> token.claims(claims -> claims.remove("sub")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.write")))
                .header("Idempotency-Key", "http-subject-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
  }

  private MvcResult create(String subject, String key, String request) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/orders")
                .with(writeJwt(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor writeJwt(
      String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.write"));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor readJwt(
      String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.read"));
  }
}
