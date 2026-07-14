package com.ledgerflow.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataSecurityIntegrationTest extends PostgreSqlIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void rejectsSensitivePaymentFieldsWithoutEchoingOrPersistingTheirValues(CapturedOutput output)
      throws Exception {
    String bodyMarker = "seeded-sensitive-body-marker";
    String idempotencyMarker = "seeded-sensitive-idempotency-marker";

    String response =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(
                        jwt()
                            .jwt(token -> token.subject("sensitive-data-test-customer"))
                            .authorities(
                                new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.write"),
                                new SimpleGrantedAuthority("ROLE_customer")))
                    .header("Idempotency-Key", idempotencyMarker)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "amount": {"amountMinor": 100, "currency": "INR"},
                          "paymentCredential": "%s",
                          "cardNumber": "not-accepted",
                          "cvv": "not-accepted"
                        }
                        """
                            .formatted(bodyMarker)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response).doesNotContain(bodyMarker, idempotencyMarker, "not-accepted");
    assertThat(output.getAll()).doesNotContain(bodyMarker, idempotencyMarker, "not-accepted");
    assertThat(jdbcClient.sql("SELECT count(*) FROM orders").query(Long.class).single()).isZero();
    assertThat(
            jdbcClient.sql("SELECT count(*) FROM idempotency_records").query(Long.class).single())
        .isZero();
  }
}
