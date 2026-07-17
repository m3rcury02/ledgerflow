package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationType;
import com.ledgerflow.operations.internal.OperationsProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

class OperatorRecoveryService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
  private static final Pattern SAFE_REASON = Pattern.compile("[^\\r\\n\\p{Cc}]{10,500}");
  private static final Pattern APPROVAL_REFERENCE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{9,99}");

  private final OperatorRecoveryStore store;
  private final OperationsProperties properties;
  private final OperatorRecoveryMetrics metrics;
  private final Clock clock;

  OperatorRecoveryService(
      OperatorRecoveryStore store,
      OperationsProperties properties,
      OperatorRecoveryMetrics metrics,
      Clock clock) {
    this.store = store;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  OperatorRecoveryViews.Page list(int requestedLimit, OperationType type, String cursor) {
    if (requestedLimit < 1 || requestedLimit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100");
    }
    int limit = requestedLimit;
    OperatorRecoveryStore.FailureCursor decoded = decodeCursor(cursor);
    List<FailedOperation> rows = store.listFailures(limit + 1, type, decoded);
    boolean hasNext = rows.size() > limit;
    List<FailedOperation> page = rows.stream().limit(limit).toList();
    String next = hasNext ? encodeCursor(page.getLast()) : null;
    return new OperatorRecoveryViews.Page(page.stream().map(this::summary).toList(), next);
  }

  OperatorRecoveryViews.Detail detail(String operationId) {
    OperationReference reference = OperationReference.parse(operationId);
    FailedOperation operation = requiredOperation(reference);
    List<OperatorRecoveryViews.Attempt> attempts =
        store.attempts(reference).stream()
            .map(
                attempt ->
                    new OperatorRecoveryViews.Attempt(
                        attempt.source(),
                        attempt.action(),
                        attempt.attemptNumber(),
                        attempt.outcome(),
                        attempt.failureCode(),
                        attempt.at()))
            .toList();
    return new OperatorRecoveryViews.Detail(summary(operation), attempts);
  }

  OperatorRecoveryViews.RetryStatus retryStatus(String operationId, UUID retryId) {
    OperationReference reference = OperationReference.parse(operationId);
    return status(
        store.findRetry(reference, retryId).orElseThrow(OperationNotFoundException::new), false);
  }

  @Transactional
  OperatorRecoveryViews.RetryStatus requestRetry(
      String operationId,
      String idempotencyKey,
      String reason,
      UUID approvalId,
      JwtAuthenticationToken authentication,
      String correlationId) {
    OperationReference reference = OperationReference.parse(operationId);
    ActorIdentity actor = ActorIdentity.authenticated(authentication);
    String safeReason = requireReason(reason);
    byte[] keyHash = keyHash(idempotencyKey);
    byte[] requestHash = requestHash(reference.externalId(), safeReason, approvalId);
    Instant now = clock.instant();

    store.lockIdempotencyKey(keyHash);
    var existing = store.findRetryByKey(actor, keyHash);
    if (existing.isPresent()) {
      RetryCommand command = existing.get();
      if (command.operationType() != reference.type()
          || !command.sourceId().equals(reference.sourceId())
          || !MessageDigest.isEqual(command.requestHash(), requestHash)) {
        metrics.retry(reference.type(), "idempotency_conflict");
        throw new OperatorConflictException(OperatorConflictException.Code.IDEMPOTENCY_KEY_REUSED);
      }
      metrics.retry(reference.type(), "idempotent_replay");
      return status(command, true);
    }
    FailedOperation operation = requiredOperation(reference);
    OperatorRecoveryStore.RecoveryState state = store.lockRecoveryState(reference, now);
    if (!operation.retryable()) {
      throw conflict(reference.type(), OperatorConflictException.Code.OPERATION_NOT_RETRYABLE);
    }
    if (store.findActive(reference).isPresent()) {
      throw conflict(reference.type(), OperatorConflictException.Code.RETRY_ALREADY_ACTIVE);
    }
    if (state.retryAvailableAt() != null && state.retryAvailableAt().isAfter(now)) {
      throw conflict(reference.type(), OperatorConflictException.Code.RETRY_COOLDOWN);
    }

    String attemptKind;
    int attemptNumber;
    int automaticAttempts = state.automaticAttempts();
    int breakGlassAttempts = state.breakGlassAttempts();
    if (approvalId == null) {
      if (automaticAttempts >= properties.maxAutomaticAttempts()) {
        throw conflict(reference.type(), OperatorConflictException.Code.RETRY_LIMIT_REACHED);
      }
      attemptKind = "AUTOMATIC";
      attemptNumber = ++automaticAttempts;
    } else {
      if (automaticAttempts < properties.maxAutomaticAttempts()
          || breakGlassAttempts >= properties.maxBreakGlassAttempts()) {
        metrics.breakGlass("rejected");
        throw conflict(reference.type(), OperatorConflictException.Code.BREAK_GLASS_NOT_AVAILABLE);
      }
      attemptKind = "BREAK_GLASS";
      attemptNumber = ++breakGlassAttempts;
    }

    RetryCommand command;
    if (approvalId != null && !store.approvalAvailable(approvalId, reference)) {
      throw conflict(reference.type(), OperatorConflictException.Code.BREAK_GLASS_APPROVAL_USED);
    }
    command =
        store.insertRetry(
            reference,
            keyHash,
            requestHash,
            attemptKind,
            attemptNumber,
            approvalId,
            safeReason,
            actor,
            correlationId,
            currentTraceparent(),
            operation.originTraceparent(),
            now);
    if (approvalId != null) {
      store.consumeApproval(approvalId, reference, command.id(), now);
      metrics.breakGlass("used");
      store.appendAudit(
          reference,
          command,
          null,
          actor,
          "BREAK_GLASS_USED",
          safeReason,
          "USED",
          correlationId,
          now);
    }
    store.reserveAttempt(reference, command, automaticAttempts, breakGlassAttempts, now);
    store.appendAudit(
        reference,
        command,
        null,
        actor,
        "RETRY_ACCEPTED",
        safeReason,
        attemptKind,
        correlationId,
        now);
    metrics.retry(reference.type(), "accepted");
    return status(command, false);
  }

  @Transactional
  OperatorRecoveryViews.Approval approveBreakGlass(
      String operationId,
      String idempotencyKey,
      String approvalReference,
      String reason,
      JwtAuthenticationToken authentication,
      String correlationId) {
    OperationReference reference = OperationReference.parse(operationId);
    ActorIdentity actor = ActorIdentity.authenticated(authentication);
    String safeReason = requireReason(reason);
    String safeReference = requireApprovalReference(approvalReference);
    byte[] keyHash = keyHash(idempotencyKey);
    byte[] requestHash = requestHash(reference.externalId(), safeReference, safeReason);
    Instant now = clock.instant();

    store.lockIdempotencyKey(keyHash);
    var existing = store.findApprovalByKey(actor, keyHash);
    if (existing.isPresent()) {
      BreakGlassApproval approval = existing.get();
      if (!MessageDigest.isEqual(approval.requestHash(), requestHash)) {
        throw new OperatorConflictException(OperatorConflictException.Code.IDEMPOTENCY_KEY_REUSED);
      }
      return new OperatorRecoveryViews.Approval(
          approval.id(), reference.externalId(), approval.createdAt(), true);
    }
    FailedOperation operation = requiredOperation(reference);
    OperatorRecoveryStore.RecoveryState state = store.lockRecoveryState(reference, now);
    if (!operation.retryable()
        || state.automaticAttempts() < properties.maxAutomaticAttempts()
        || state.breakGlassAttempts() >= properties.maxBreakGlassAttempts()) {
      metrics.breakGlass("rejected");
      throw new OperatorConflictException(OperatorConflictException.Code.BREAK_GLASS_NOT_AVAILABLE);
    }
    BreakGlassApproval approval =
        store.insertApproval(
            reference,
            keyHash,
            requestHash,
            safeReference,
            safeReason,
            actor,
            correlationId,
            currentTraceparent(),
            now);
    store.appendAudit(
        reference,
        null,
        approval,
        actor,
        "BREAK_GLASS_APPROVED",
        safeReason,
        "APPROVED",
        correlationId,
        now);
    metrics.breakGlass("approved");
    return new OperatorRecoveryViews.Approval(
        approval.id(), reference.externalId(), approval.createdAt(), false);
  }

  private OperatorConflictException conflict(
      OperationType type, OperatorConflictException.Code code) {
    String outcome =
        switch (code) {
          case RETRY_ALREADY_ACTIVE -> "already_active";
          case RETRY_COOLDOWN -> "cooldown";
          case RETRY_LIMIT_REACHED -> "limit";
          case IDEMPOTENCY_KEY_REUSED -> "idempotency_conflict";
          case BREAK_GLASS_NOT_AVAILABLE, BREAK_GLASS_APPROVAL_USED, OPERATION_NOT_RETRYABLE ->
              "rejected";
        };
    metrics.retry(type, outcome);
    return new OperatorConflictException(code);
  }

  private FailedOperation requiredOperation(OperationReference reference) {
    return store.findOperation(reference).orElseThrow(OperationNotFoundException::new);
  }

  private OperatorRecoveryViews.Summary summary(FailedOperation operation) {
    return new OperatorRecoveryViews.Summary(
        operation.operationId(),
        operation.type(),
        operation.status(),
        operation.failureCode(),
        operation.summary(),
        operation.attemptCount(),
        operation.failedAt(),
        operation.updatedAt(),
        operation.retryable());
  }

  private OperatorRecoveryViews.RetryStatus status(RetryCommand command, boolean replayed) {
    return new OperatorRecoveryViews.RetryStatus(
        command.id(),
        new OperationReference(command.operationType(), command.sourceId()).externalId(),
        command.status(),
        command.attemptKind(),
        command.attemptNumber(),
        command.resultCode(),
        command.failureCode(),
        command.acceptedAt(),
        command.completedAt(),
        replayed);
  }

  private String requireReason(String reason) {
    String value = reason == null ? "" : reason.strip();
    if (!SAFE_REASON.matcher(value).matches()) {
      throw new IllegalArgumentException("audit reason is invalid");
    }
    return value;
  }

  private String requireApprovalReference(String approvalReference) {
    String value = approvalReference == null ? "" : approvalReference.strip();
    if (!APPROVAL_REFERENCE.matcher(value).matches()) {
      throw new IllegalArgumentException("approval reference is invalid");
    }
    return value;
  }

  private byte[] keyHash(String key) {
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("idempotency key is invalid");
    }
    return sha256(key.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] requestHash(Object... values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Object value : values) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String currentTraceparent() {
    SpanContext context = Span.current().getSpanContext();
    if (!context.isValid()) {
      return null;
    }
    return "00-"
        + context.getTraceId()
        + '-'
        + context.getSpanId()
        + '-'
        + context.getTraceFlags().asHex();
  }

  private String encodeCursor(FailedOperation operation) {
    String value = operation.failedAt() + "|" + operation.type() + "|" + operation.sourceId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private OperatorRecoveryStore.FailureCursor decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    if (cursor.length() > 256) {
      throw new IllegalArgumentException("cursor is invalid");
    }
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 3) {
        throw new IllegalArgumentException("cursor is invalid");
      }
      return new OperatorRecoveryStore.FailureCursor(
          Instant.parse(parts[0]), OperationType.valueOf(parts[1]), UUID.fromString(parts[2]));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("cursor is invalid");
    }
  }
}
