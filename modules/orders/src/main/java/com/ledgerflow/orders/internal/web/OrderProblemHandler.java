package com.ledgerflow.orders.internal.web;

import com.ledgerflow.orders.internal.application.IdempotencyConflictException;
import com.ledgerflow.orders.internal.application.IdempotencyUnavailableException;
import com.ledgerflow.orders.internal.application.OrderNotFoundException;
import com.ledgerflow.orders.internal.application.ProviderProtocolException;
import com.ledgerflow.orders.internal.domain.UnsupportedCurrencyException;
import com.ledgerflow.payments.api.PaymentWorkflowUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class OrderProblemHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderProblemHandler.class);

  private final ProblemDetailsFactory problemDetailsFactory;

  public OrderProblemHandler(ProblemDetailsFactory problemDetailsFactory) {
    this.problemDetailsFactory = problemDetailsFactory;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> validation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            HttpStatus.BAD_REQUEST,
            "invalid-request",
            "Invalid request",
            "One or more request fields are invalid.",
            "invalid_request",
            request);
    List<Map<String, String>> errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    Map.of(
                        "field",
                        "/" + error.getField().replace('.', '/'),
                        "code",
                        validationCode(error.getCode())))
            .toList();
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ProblemDetail> malformed(Exception exception, HttpServletRequest request) {
    if (causedByPayloadLimit(exception)) {
      return problem(
          HttpStatus.CONTENT_TOO_LARGE,
          "payload-too-large",
          "Payload too large",
          "The request payload exceeds the configured limit.",
          "payload_too_large",
          request);
    }
    return problem(
        HttpStatus.BAD_REQUEST,
        "invalid-request",
        "Invalid request",
        "The request body, path, or required header is invalid.",
        "invalid_request",
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> unsupportedMediaType(
      HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "unsupported-media-type",
        "Unsupported media type",
        "This operation accepts an uncompressed application/json request.",
        "unsupported_media_type",
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ProblemDetail> unacceptableResponseType(
      HttpMediaTypeNotAcceptableException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_ACCEPTABLE,
        "not-acceptable",
        "Not acceptable",
        "This operation returns application/json or application/problem+json.",
        "not_acceptable",
        request);
  }

  @ExceptionHandler(UnsupportedCurrencyException.class)
  ResponseEntity<ProblemDetail> unsupportedCurrency(
      UnsupportedCurrencyException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "unsupported-currency",
        "Unsupported currency",
        "The MVP supports INR only.",
        "unsupported_currency",
        request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<ProblemDetail> idempotencyConflict(
      IdempotencyConflictException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "idempotency-key-reused",
        "Idempotency key reused",
        "The idempotency key was already used with a different request.",
        "idempotency_key_reused",
        request);
  }

  @ExceptionHandler(ProviderProtocolException.class)
  ResponseEntity<ProblemDetail> providerProtocol(
      ProviderProtocolException exception, HttpServletRequest request) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .header(org.springframework.http.HttpHeaders.LOCATION, exception.location());
    if (exception.replayed()) {
      response.header("Idempotency-Replayed", "true");
    }
    return response.body(
        problemDetailsFactory.create(
            HttpStatus.BAD_GATEWAY,
            "provider-protocol-error",
            "Payment provider protocol error",
            "The payment provider returned an invalid response.",
            "provider_protocol_error",
            request));
  }

  @ExceptionHandler(OrderNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(
      OrderNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "resource-not-found",
        "Resource not found",
        "The requested order was not found.",
        "resource_not_found",
        request);
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ProblemDetail> invalidAuthentication(
      AuthenticationException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        "unauthorized",
        "Unauthorized",
        "A valid bearer token is required.",
        "unauthorized",
        request);
  }

  @ExceptionHandler({
    IdempotencyUnavailableException.class,
    PaymentWorkflowUnavailableException.class,
    TransientDataAccessException.class
  })
  ResponseEntity<ProblemDetail> temporarilyUnavailable(
      Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "service-unavailable",
        "Service unavailable",
        "The operation could not be completed safely. Retry with the same idempotency key.",
        "service_unavailable",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> invalidArgument(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "invalid-request",
        "Invalid request",
        "The request contains an invalid value.",
        "invalid_request",
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
    LOGGER.error(
        "Unhandled order HTTP failure: errorType={}", exception.getClass().getSimpleName());
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "Internal server error",
        "The request could not be completed.",
        "internal_error",
        request);
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpStatus status,
      String type,
      String title,
      String detail,
      String code,
      HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(problemDetailsFactory.create(status, type, title, detail, code, request));
  }

  private String validationCode(String code) {
    if (code == null) {
      return "invalid";
    }
    return switch (code) {
      case "Positive" -> "must_be_positive";
      case "NotBlank", "NotNull" -> "required";
      case "Size" -> "invalid_length";
      case "Pattern" -> "invalid_format";
      default -> "invalid";
    };
  }

  private boolean causedByPayloadLimit(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PayloadTooLargeException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
