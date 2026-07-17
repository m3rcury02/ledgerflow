package com.ledgerflow.operations.internal.recovery;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OperatorRecoveryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class OperatorRecoveryProblemHandler {

  private static final String CORRELATION_ATTRIBUTE =
      "com.ledgerflow.orders.internal.web.CorrelationIdFilter.correlationId";

  @ExceptionHandler(OperationNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.NOT_FOUND,
        "operation-not-found",
        "Operation not found",
        "The requested operation was not found.",
        "operation_not_found");
  }

  @ExceptionHandler(OperatorConflictException.class)
  ResponseEntity<ProblemDetail> conflict(
      OperatorConflictException exception, HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.CONFLICT,
        "operation-recovery-conflict",
        "Recovery request conflict",
        detail(exception.code()),
        exception.code().name().toLowerCase(java.util.Locale.ROOT));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    MissingRequestHeaderException.class
  })
  ResponseEntity<ProblemDetail> invalid(HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.BAD_REQUEST,
        "invalid-request",
        "Invalid request",
        "The recovery request is invalid.",
        "invalid_request");
  }

  private String detail(OperatorConflictException.Code code) {
    return switch (code) {
      case IDEMPOTENCY_KEY_REUSED ->
          "The idempotency key was already used for a different recovery request.";
      case RETRY_ALREADY_ACTIVE -> "A recovery command is already active for this operation.";
      case RETRY_COOLDOWN -> "The recovery cooldown has not elapsed.";
      case RETRY_LIMIT_REACHED -> "The automatic recovery-attempt limit was reached.";
      case BREAK_GLASS_NOT_AVAILABLE -> "Valid break-glass approval evidence is required.";
      case BREAK_GLASS_APPROVAL_USED -> "The break-glass approval is invalid or already used.";
      case OPERATION_NOT_RETRYABLE -> "The operation is not currently recoverable.";
    };
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpServletRequest request,
      HttpStatus status,
      String slug,
      String title,
      String detail,
      String code) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("https://ledgerflow.example/problems/" + slug));
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CORRELATION_ATTRIBUTE);
    problem.setProperty(
        "correlationId", correlationId instanceof String value ? value : "unavailable");
    return ResponseEntity.status(status).body(problem);
  }
}
