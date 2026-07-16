package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationRetryHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorRecoveryService {
  private final OperatorRetryCommandRepository repository;
  private final Map<String, OperationRetryHandler> handlers;
  private final Clock clock;

  public OperatorRecoveryService(
      OperatorRetryCommandRepository repository,
      List<OperationRetryHandler> handlerList,
      Clock clock) {
    this.repository = repository;
    this.handlers =
        handlerList.stream()
            .collect(Collectors.toMap(OperationRetryHandler::getSupportedOperationType, h -> h));
    this.clock = clock;
  }

  @Transactional
  public OperatorRetryCommand submitRetry(
      UUID operationId,
      String operationType,
      String idempotencyKey,
      String requestBody,
      String reason,
      boolean breakGlass,
      String approvalReference,
      String issuer,
      String subject,
      String clientId) {
    byte[] keyHash = hash(idempotencyKey);
    byte[] reqHash = hash(requestBody);

    return repository
        .findByIdempotencyKeyHash(keyHash)
        .orElseGet(
            () -> {
              var command =
                  new OperatorRetryCommand(
                      null,
                      keyHash,
                      reqHash,
                      operationId,
                      operationType,
                      "PENDING",
                      reason,
                      breakGlass,
                      approvalReference,
                      issuer,
                      subject,
                      clientId,
                      null,
                      null,
                      null,
                      clock.instant(),
                      null);
              return repository.save(command);
            });
  }

  private byte[] hash(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }
}
