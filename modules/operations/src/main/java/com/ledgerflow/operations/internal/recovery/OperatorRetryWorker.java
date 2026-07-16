package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationRetryHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OperatorRetryWorker {

  private static final Logger log = LoggerFactory.getLogger(OperatorRetryWorker.class);

  private final OperatorRetryCommandRepository repository;
  private final Map<String, OperationRetryHandler> handlers;
  private final Clock clock;
  private final String instanceId = java.util.UUID.randomUUID().toString();

  public OperatorRetryWorker(
      OperatorRetryCommandRepository repository,
      List<OperationRetryHandler> handlerList,
      Clock clock) {
    this.repository = repository;
    this.handlers =
        handlerList.stream()
            .collect(Collectors.toMap(OperationRetryHandler::getSupportedOperationType, h -> h));
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 5000)
  public void processPendingCommands() {
    try {
      Instant now = clock.instant();
      List<OperatorRetryCommand> commands = repository.claimPendingCommands(now, 10);

      for (OperatorRetryCommand command : commands) {
        // Lease the command
        OperatorRetryCommand leased = command.withLease(instanceId, now.plusSeconds(30));
        repository.save(leased);

        try {
          OperationRetryHandler handler = handlers.get(leased.operationType());
          if (handler != null) {
            handler.handleRetry(
                leased.id(),
                leased.operationId(),
                new String(leased.idempotencyKeyHash()),
                leased.breakGlass());
            repository.save(leased.withResolution("COMPLETED", null, clock.instant()));
          } else {
            repository.save(
                leased.withResolution("FAILED", "UNSUPPORTED_OPERATION_TYPE", clock.instant()));
          }
        } catch (Exception e) {
          log.error("Failed to process retry command {}", leased.id(), e);
          repository.save(leased.withResolution("FAILED", "INTERNAL_ERROR", clock.instant()));
        }
      }
    } catch (Exception e) {
      log.error("Failed to claim pending commands", e);
    }
  }
}
