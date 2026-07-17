package com.ledgerflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.messaging.internal.application.OutboxPublisher;
import com.ledgerflow.testing.KafkaIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class LedgerFlowMvpEndToEndTest extends KafkaIntegrationTest {

  private static final String CUSTOMER = "mvp-proof-customer";
  private static final String KEY = "mvp-proof-order-key-0001";
  private static final String REQUEST =
      """
      {
        "clientReference": "mvp-proof-order",
        "amount": {"amountMinor": 259900, "currency": "INR"},
        "paymentMethodReference": "pm_mock_success"
      }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private OutboxPublisher outboxPublisher;

  @Test
  void completedPublicOrderReplaysExactlyAndDeliversOneAsynchronousNotification() throws Exception {
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(customerJwt("ledgerflow.orders.write"))
                    .header("Idempotency-Key", KEY)
                    .header("X-Correlation-Id", "mvp-proof-correlation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REQUEST))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "mvp-proof-correlation"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.payment.status").value("CAPTURED"))
            .andReturn();
    String response = created.getResponse().getContentAsString();
    String orderId = JsonPath.read(response, "$.orderId");

    MvcResult replay =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(customerJwt("ledgerflow.orders.write"))
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REQUEST))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andReturn();
    assertThat(replay.getResponse().getContentAsString()).isEqualTo(response);

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(customerJwt("ledgerflow.orders.write"))
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("259900", "259901")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_key_reused"));

    mockMvc
        .perform(
            get("/api/v1/orders/{orderId}", orderId).with(customerJwt("ledgerflow.orders.read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    UUID authorizationId =
        jdbcClient.sql("SELECT authorization_request_id FROM payments").query(UUID.class).single();
    UUID captureId =
        jdbcClient.sql("SELECT capture_request_id FROM payments").query(UUID.class).single();
    assertThat(PROVIDER.callCount("AUTHORIZATION", authorizationId)).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureId)).isOne();
    assertThat(count("orders")).isOne();
    assertThat(count("payments")).isOne();
    assertThat(count("ledger_transactions")).isOne();
    assertThat(count("ledger_entries")).isEqualTo(2);
    assertThat(debitTotal()).isEqualTo(creditTotal()).isEqualTo(259_900);
    assertThat(count("outbox_events")).isOne();
    assertThat(outboxStatus()).isEqualTo("PENDING");
    assertThat(count("notifications")).isZero();

    outboxPublisher.publishBatch();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(outboxStatus()).isEqualTo("PUBLISHED");
              assertThat(count("notification_inbox")).isOne();
              assertThat(count("notifications")).isOne();
            });
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt(
      String scope) {
    return jwt()
        .jwt(token -> token.subject(CUSTOMER))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_" + scope),
            new SimpleGrantedAuthority("ROLE_customer"));
  }

  private long count(String table) {
    return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private long debitTotal() {
    return ledgerTotal("D");
  }

  private long creditTotal() {
    return ledgerTotal("C");
  }

  private long ledgerTotal(String side) {
    return jdbcClient
        .sql("SELECT coalesce(sum(amount_minor), 0) FROM ledger_entries WHERE side = :side")
        .param("side", side)
        .query(Long.class)
        .single();
  }

  private String outboxStatus() {
    return jdbcClient.sql("SELECT status FROM outbox_events").query(String.class).single();
  }
}
