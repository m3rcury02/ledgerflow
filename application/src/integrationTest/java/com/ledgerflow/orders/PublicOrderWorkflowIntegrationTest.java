package com.ledgerflow.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.testing.payment.PaymentIntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class PublicOrderWorkflowIntegrationTest extends PaymentIntegrationTestSupport {

  @Autowired private MockMvc mockMvc;

  @Test
  void exposesAuthorizationAndCaptureDeclinesTruthfullyWithoutFinancialEffects() throws Exception {
    MvcResult authorization = create("decline-auth-key-0001", "pm_mock_authorization_decline", 201);
    MvcResult capture = create("decline-capture-key-0001", "pm_mock_capture_decline", 201);

    assertThat(path(authorization, "$.status")).isEqualTo("PAYMENT_DECLINED");
    assertThat(path(authorization, "$.payment.status")).isEqualTo("DECLINED");
    assertThat(path(capture, "$.status")).isEqualTo("PAYMENT_DECLINED");
    assertThat(path(capture, "$.payment.status")).isEqualTo("CAPTURE_DECLINED");
    assertThat(count("ledger_transactions")).isZero();
    assertThat(count("outbox_events")).isZero();
  }

  @Test
  void returnsAndReplaysRetryPendingAfterBoundedTemporaryFailure() throws Exception {
    MvcResult pending =
        create("temporary-pending-key-0001", "pm_mock_persistent_temporary_error", 202);
    UUID paymentId = UUID.fromString(path(pending, "$.payment.paymentId"));
    UUID requestId = authorizationRequestId(paymentId);

    mockMvc
        .perform(request("temporary-pending-key-0001", "pm_mock_persistent_temporary_error"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("PAYMENT_RETRY_PENDING"));

    assertThat(PROVIDER.callCount("AUTHORIZATION", requestId)).isEqualTo(2);
    assertThat(count("ledger_transactions")).isZero();
  }

  @Test
  void retriesAConfirmedTemporaryCaptureFailureWithinTheBoundAndCompletes() throws Exception {
    MvcResult completed =
        create("capture-temporary-key-0001", "pm_mock_capture_temporary_error", 201);
    UUID paymentId = UUID.fromString(path(completed, "$.payment.paymentId"));

    assertThat(path(completed, "$.status")).isEqualTo("COMPLETED");
    assertThat(PROVIDER.callCount("AUTHORIZATION", authorizationRequestId(paymentId))).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureRequestId(paymentId))).isEqualTo(2);
  }

  @Test
  void reconcilesTimedOutProviderSuccessByLookupWithoutResend() throws Exception {
    MvcResult completed = create("timeout-lookup-key-0001", "pm_mock_authorization_timeout", 201);
    UUID paymentId = UUID.fromString(path(completed, "$.payment.paymentId"));

    assertThat(path(completed, "$.status")).isEqualTo("COMPLETED");
    assertThat(PROVIDER.callCount("AUTHORIZATION", authorizationRequestId(paymentId))).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureRequestId(paymentId))).isOne();
  }

  @Test
  void reconcilesTimedOutCaptureSuccessByLookupWithoutResend() throws Exception {
    MvcResult completed = create("capture-timeout-lookup-key", "pm_mock_capture_timeout", 201);
    UUID paymentId = UUID.fromString(path(completed, "$.payment.paymentId"));

    assertThat(path(completed, "$.status")).isEqualTo("COMPLETED");
    assertThat(PROVIDER.callCount("AUTHORIZATION", authorizationRequestId(paymentId))).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureRequestId(paymentId))).isOne();
  }

  @Test
  void resendsOnlyTheSameOperationIdAfterTimedOutLookupReturnsNotFound() throws Exception {
    MvcResult completed =
        create("timeout-not-found-key-0001", "pm_mock_authorization_timeout_not_found", 201);
    UUID paymentId = UUID.fromString(path(completed, "$.payment.paymentId"));
    UUID authorizationRequestId = authorizationRequestId(paymentId);

    assertThat(path(completed, "$.payment.status")).isEqualTo("CAPTURED");
    assertThat(PROVIDER.callCount("AUTHORIZATION", authorizationRequestId)).isEqualTo(2);
    assertThat(
            jdbcClient
                .sql(
                    """
                    SELECT count(DISTINCT provider_request_id)
                    FROM payment_attempt_history
                    WHERE payment_id = :paymentId AND stage = 'AUTHORIZATION'
                    """)
                .param("paymentId", paymentId)
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void captureTimeoutNotFoundResendsOnlyTheStableCaptureOperation() throws Exception {
    MvcResult completed =
        create("capture-timeout-not-found-key", "pm_mock_capture_timeout_not_found", 201);
    UUID paymentId = UUID.fromString(path(completed, "$.payment.paymentId"));

    assertThat(PROVIDER.callCount("AUTHORIZATION", authorizationRequestId(paymentId))).isOne();
    assertThat(PROVIDER.callCount("CAPTURE", captureRequestId(paymentId))).isEqualTo(2);
    assertThat(
            jdbcClient
                .sql(
                    """
                    SELECT count(DISTINCT provider_request_id)
                    FROM payment_attempt_history
                    WHERE payment_id = :paymentId AND stage = 'CAPTURE'
                    """)
                .param("paymentId", paymentId)
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void persistsFailedOrderAndReplaysSanitizedProviderProtocolProblem() throws Exception {
    MvcResult failure =
        mockMvc
            .perform(request("invalid-provider-key-0001", "pm_mock_invalid_response"))
            .andExpect(status().isBadGateway())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.code").value("provider_protocol_error"))
            .andReturn();
    String location = failure.getResponse().getHeader("Location");

    mockMvc
        .perform(request("invalid-provider-key-0001", "pm_mock_invalid_response"))
        .andExpect(status().isBadGateway())
        .andExpect(header().string("Location", location))
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.code").value("provider_protocol_error"));

    mockMvc
        .perform(get(location).with(readJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.payment.status").value("FAILED"));
    assertThat(count("ledger_transactions")).isZero();
  }

  private MvcResult create(String key, String scenario, int expectedStatus) throws Exception {
    return mockMvc
        .perform(request(key, scenario))
        .andExpect(status().is(expectedStatus))
        .andExpect(header().exists("Location"))
        .andReturn();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
      String key, String scenario) {
    return post("/api/v1/orders")
        .with(writeJwt())
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "clientReference": "workflow-test",
              "amount": {"amountMinor": 259900, "currency": "INR"},
              "paymentMethodReference": "%s"
            }
            """
                .formatted(scenario));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor writeJwt() {
    return jwt()
        .jwt(token -> token.subject("workflow-customer"))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.write"),
            new SimpleGrantedAuthority("ROLE_customer"));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor readJwt() {
    return jwt()
        .jwt(token -> token.subject("workflow-customer"))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_ledgerflow.orders.read"),
            new SimpleGrantedAuthority("ROLE_customer"));
  }

  private String path(MvcResult result, String path) throws Exception {
    return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
  }

  private long count(String table) {
    return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private UUID authorizationRequestId(UUID paymentId) {
    return requestId(paymentId, "authorization_request_id");
  }

  private UUID captureRequestId(UUID paymentId) {
    return requestId(paymentId, "capture_request_id");
  }

  private UUID requestId(UUID paymentId, String column) {
    return jdbcClient
        .sql("SELECT " + column + " FROM payments WHERE id = :paymentId")
        .param("paymentId", paymentId)
        .query(UUID.class)
        .single();
  }
}
