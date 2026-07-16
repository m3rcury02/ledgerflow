package com.ledgerflow.operations.internal.recovery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/failed-operations")
public class OperatorFailedOperationsController {

  private final FailedOperationProjectionRepository projectionRepository;
  private final OperatorRecoveryService recoveryService;

  public OperatorFailedOperationsController(
      FailedOperationProjectionRepository projectionRepository,
      OperatorRecoveryService recoveryService) {
    this.projectionRepository = projectionRepository;
    this.recoveryService = recoveryService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> listFailedOperations(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String pageToken) {

    List<FailedOperationProjection> items = projectionRepository.findAllPaginated(limit);
    Map<String, Object> response = new HashMap<>();
    response.put("items", items);
    response.put("nextPageToken", null);

    return ResponseEntity.ok(response);
  }

  @GetMapping("/{operationId}")
  public ResponseEntity<Map<String, Object>> getFailedOperation(@PathVariable UUID operationId) {
    return projectionRepository
        .findById(operationId)
        .map(
            proj -> {
              Map<String, Object> response = new HashMap<>();
              response.put("summary", proj);
              response.put("attempts", List.of()); // placeholder for attempt history
              return ResponseEntity.ok(response);
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/{operationId}/retry")
  public ResponseEntity<Map<String, Object>> retryFailedOperation(
      @PathVariable UUID operationId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal Jwt jwt) {

    return projectionRepository
        .findById(operationId)
        .map(
            proj -> {
              String issuer = jwt.getIssuer().toString();
              String subject = jwt.getSubject();
              String clientId = jwt.getClaimAsString("azp");

              OperatorRetryCommand command =
                  recoveryService.submitRetry(
                      operationId,
                      proj.operationType(),
                      idempotencyKey,
                      body.toString(),
                      body.get("reason"),
                      false,
                      null,
                      issuer,
                      subject,
                      clientId);

              Map<String, Object> response = new HashMap<>();
              response.put("commandId", command.id());
              response.put("status", command.status());
              response.put("acceptedAt", command.createdAt());

              return ResponseEntity.accepted().body(response);
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/{operationId}/break-glass-retry")
  public ResponseEntity<Map<String, Object>> breakGlassRetryFailedOperation(
      @PathVariable UUID operationId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal Jwt jwt) {

    return projectionRepository
        .findById(operationId)
        .map(
            proj -> {
              String issuer = jwt.getIssuer().toString();
              String subject = jwt.getSubject();
              String clientId = jwt.getClaimAsString("azp");

              OperatorRetryCommand command =
                  recoveryService.submitRetry(
                      operationId,
                      proj.operationType(),
                      idempotencyKey,
                      body.toString(),
                      body.get("reason"),
                      true,
                      body.get("approvalReference"),
                      issuer,
                      subject,
                      clientId);

              Map<String, Object> response = new HashMap<>();
              response.put("commandId", command.id());
              response.put("status", command.status());
              response.put("acceptedAt", command.createdAt());

              return ResponseEntity.accepted().body(response);
            })
        .orElse(ResponseEntity.notFound().build());
  }
}
