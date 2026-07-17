package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/operations")
final class OperatorRecoveryController {

  private static final String CORRELATION_ATTRIBUTE =
      "com.ledgerflow.orders.internal.web.CorrelationIdFilter.correlationId";
  private static final String REASON_PATTERN = "^[^\\r\\n\\p{Cc}]+$";
  private static final String APPROVAL_REFERENCE_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{9,99}$";

  private final OperatorRecoveryService service;

  OperatorRecoveryController(OperatorRecoveryService service) {
    this.service = service;
  }

  @GetMapping
  OperatorRecoveryViews.Page list(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) OperationType type,
      @RequestParam(required = false) @Size(max = 256) String cursor) {
    return service.list(limit, type, cursor);
  }

  @GetMapping("/{operationId}")
  OperatorRecoveryViews.Detail detail(@PathVariable String operationId) {
    return service.detail(operationId);
  }

  @PostMapping("/{operationId}/retries")
  ResponseEntity<OperatorRecoveryViews.RetryStatus> retry(
      @PathVariable String operationId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody RetryRequest requestBody,
      JwtAuthenticationToken authentication,
      HttpServletRequest request) {
    OperatorRecoveryViews.RetryStatus response =
        service.requestRetry(
            operationId,
            idempotencyKey,
            requestBody.reason(),
            requestBody.approvalId(),
            authentication,
            correlationId(request));
    String location =
        "/api/v1/operator/operations/" + operationId + "/retries/" + response.retryId();
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, location)
        .header("Idempotency-Replayed", Boolean.toString(response.replayed()))
        .body(response);
  }

  @GetMapping("/{operationId}/retries/{retryId}")
  OperatorRecoveryViews.RetryStatus retryStatus(
      @PathVariable String operationId, @PathVariable UUID retryId) {
    return service.retryStatus(operationId, retryId);
  }

  @PostMapping("/{operationId}/break-glass-approvals")
  ResponseEntity<OperatorRecoveryViews.Approval> approve(
      @PathVariable String operationId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody BreakGlassApprovalRequest requestBody,
      JwtAuthenticationToken authentication,
      HttpServletRequest request) {
    OperatorRecoveryViews.Approval response =
        service.approveBreakGlass(
            operationId,
            idempotencyKey,
            requestBody.approvalReference(),
            requestBody.reason(),
            authentication,
            correlationId(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header("Idempotency-Replayed", Boolean.toString(response.replayed()))
        .body(response);
  }

  private String correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CORRELATION_ATTRIBUTE);
    if (value instanceof String correlationId) {
      return correlationId;
    }
    throw new IllegalStateException("correlation ID was not established");
  }

  record RetryRequest(
      @NotBlank @Size(min = 10, max = 500) @Pattern(regexp = REASON_PATTERN) String reason,
      UUID approvalId) {}

  record BreakGlassApprovalRequest(
      @NotBlank @Pattern(regexp = APPROVAL_REFERENCE_PATTERN) String approvalReference,
      @NotBlank @Size(min = 10, max = 500) @Pattern(regexp = REASON_PATTERN) String reason) {}
}
