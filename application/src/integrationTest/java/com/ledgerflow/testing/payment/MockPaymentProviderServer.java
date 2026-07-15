package com.ledgerflow.testing.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MockPaymentProviderServer {

  private static final Duration TIMEOUT_RESPONSE_DELAY = Duration.ofMillis(1_500);
  private static final Duration SLOW_RESPONSE_DELAY = Duration.ofMillis(400);

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<OperationKey, Operation> operations = new ConcurrentHashMap<>();
  private final Map<OperationKey, Integer> calls = new ConcurrentHashMap<>();
  private final Map<String, String> authorizationScenarios = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<String> traceparents = new ConcurrentLinkedQueue<>();
  private final AtomicReference<CountDownLatch> slowCallStarted =
      new AtomicReference<>(new CountDownLatch(1));
  private final HttpServer server;

  public MockPaymentProviderServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not create the mock payment provider", exception);
    }
    server.createContext("/mock-provider/v1/authorizations", this::authorize);
    server.createContext("/mock-provider/v1/captures", this::capture);
    server.createContext("/mock-provider/v1/operations", this::lookup);
    server.setExecutor(
        Executors.newCachedThreadPool(
            task -> Thread.ofPlatform().daemon().name("mock-payment-provider").unstarted(task)));
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void reset() {
    operations.clear();
    calls.clear();
    authorizationScenarios.clear();
    traceparents.clear();
    slowCallStarted.set(new CountDownLatch(1));
  }

  public void stop() {
    server.stop(0);
  }

  public int callCount(String stage, UUID requestId) {
    return calls.getOrDefault(new OperationKey(stage, requestId), 0);
  }

  public java.util.List<String> traceparents() {
    return java.util.List.copyOf(traceparents);
  }

  public boolean awaitSlowCall(Duration timeout) {
    try {
      return slowCallStarted.get().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for the slow provider call", exception);
    }
  }

  private void authorize(HttpExchange exchange) {
    handle(
        exchange,
        () -> {
          Request request = request(exchange, "AUTHORIZATION");
          String scenario = requiredText(request.body(), "paymentMethodReference");
          return execute(request, scenario, "auth-", "AUTHORIZED", "AUTHORIZATION_DECLINED");
        });
  }

  private void capture(HttpExchange exchange) {
    handle(
        exchange,
        () -> {
          Request request = request(exchange, "CAPTURE");
          String authorizationReference = requiredText(request.body(), "providerAuthorizationId");
          String scenario = authorizationScenarios.get(authorizationReference);
          if (scenario == null) {
            return new Response(422, failure("DECLINED", "AUTHORIZATION_NOT_FOUND"), null);
          }
          return execute(request, scenario, "capture-", "CAPTURED", "CAPTURE_DECLINED");
        });
  }

  private void lookup(HttpExchange exchange) {
    handle(
        exchange,
        () -> {
          if (!"GET".equals(exchange.getRequestMethod())) {
            return new Response(405, "", null);
          }
          requireCorrelationId(exchange);
          captureTraceparent(exchange);
          String[] path = exchange.getRequestURI().getPath().split("/");
          if (path.length != 6) {
            return new Response(404, "", null);
          }
          String stage = path[4];
          UUID requestId = UUID.fromString(path[5]);
          Operation operation = operations.get(new OperationKey(stage, requestId));
          if (operation == null) {
            return new Response(404, "", null);
          }
          return new Response(200, operation.responseBody(), null);
        });
  }

  private Response execute(
      Request request,
      String scenario,
      String referencePrefix,
      String successOutcome,
      String declineCode) {
    OperationKey key = new OperationKey(request.stage(), request.requestId());
    int callNumber = calls.merge(key, 1, Integer::sum);
    byte[] requestHash = sha256(request.rawBody());
    Operation existing = operations.get(key);
    if (existing != null) {
      if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
        return new Response(409, failure("CONFLICT", "IDEMPOTENCY_CONFLICT"), null);
      }
      return new Response(existing.status(), existing.responseBody(), null);
    }

    if ("pm_mock_persistent_temporary_error".equals(scenario)
        || (isTemporary(scenario, request.stage()) && callNumber == 1)) {
      return new Response(503, failure("TEMPORARY_ERROR", "PROVIDER_TEMPORARY_FAILURE"), null);
    }
    if ("pm_mock_invalid_response".equals(scenario)) {
      return new Response(200, "{\"outcome\":\"IMPOSSIBLE\"}", null);
    }

    if (isTimeoutWithoutPersistence(scenario, request.stage()) && callNumber == 1) {
      return new Response(
          200,
          success(successOutcome, referencePrefix + request.requestId()),
          TIMEOUT_RESPONSE_DELAY);
    }

    boolean declined = isDecline(scenario, request.stage());
    String providerReference = referencePrefix + request.requestId();
    String responseBody =
        declined ? failure("DECLINED", declineCode) : success(successOutcome, providerReference);
    int status = declined ? 422 : 200;
    Operation operation = new Operation(requestHash, status, responseBody);
    Operation raced = operations.putIfAbsent(key, operation);
    if (raced != null) {
      if (!MessageDigest.isEqual(raced.requestHash(), requestHash)) {
        return new Response(409, failure("CONFLICT", "IDEMPOTENCY_CONFLICT"), null);
      }
      operation = raced;
      status = raced.status();
      responseBody = raced.responseBody();
    }

    if ("AUTHORIZATION".equals(request.stage()) && !declined) {
      authorizationScenarios.put(providerReference, scenario);
    }

    if (isTimeout(scenario, request.stage())) {
      return new Response(status, responseBody, TIMEOUT_RESPONSE_DELAY);
    }
    if ("pm_mock_slow_response".equals(scenario)) {
      slowCallStarted.get().countDown();
      return new Response(status, responseBody, SLOW_RESPONSE_DELAY);
    }
    return new Response(status, responseBody, null);
  }

  private Request request(HttpExchange exchange, String stage) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      throw new BadRequestException(405);
    }
    String requestIdHeader = exchange.getRequestHeaders().getFirst("X-Provider-Request-Id");
    if (requestIdHeader == null) {
      throw new BadRequestException(400);
    }
    UUID requestId = UUID.fromString(requestIdHeader);
    requireCorrelationId(exchange);
    captureTraceparent(exchange);
    String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    JsonNode body = objectMapper.readTree(rawBody);
    return new Request(stage, requestId, rawBody, body);
  }

  private void captureTraceparent(HttpExchange exchange) {
    String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
    if (traceparent != null) {
      if (!traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
        throw new BadRequestException(400);
      }
      traceparents.add(traceparent);
    }
  }

  private void requireCorrelationId(HttpExchange exchange) {
    String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
    if (correlationId == null
        || correlationId.length() > 64
        || !correlationId.matches("[A-Za-z0-9._-]+")) {
      throw new BadRequestException(400);
    }
  }

  private void handle(HttpExchange exchange, ExchangeHandler handler) {
    try (exchange) {
      Response response = handler.handle();
      if (response.delay() != null) {
        Thread.sleep(response.delay());
      }
      send(exchange, response.status(), response.body());
    } catch (BadRequestException exception) {
      sendQuietly(exchange, exception.status(), "");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      sendQuietly(exchange, 500, "");
    } catch (Exception exception) {
      sendQuietly(exchange, 500, "");
    }
  }

  private void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (!body.isEmpty()) {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
    }
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private void sendQuietly(HttpExchange exchange, int status, String body) {
    try {
      send(exchange, status, body);
    } catch (IOException ignored) {
      // The client may have timed out before the deterministic provider writes its response.
    }
  }

  private String success(String outcome, String providerReference) {
    return "{\"outcome\":\"" + outcome + "\",\"providerReference\":\"" + providerReference + "\"}";
  }

  private String failure(String outcome, String failureCode) {
    return "{\"outcome\":\"" + outcome + "\",\"failureCode\":\"" + failureCode + "\"}";
  }

  private String requiredText(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw new BadRequestException(400);
    }
    return value.asString();
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  private boolean isTemporary(String scenario, String stage) {
    return "pm_mock_temporary_error".equals(scenario)
        || ("AUTHORIZATION".equals(stage)
            && "pm_mock_authorization_temporary_error".equals(scenario))
        || ("CAPTURE".equals(stage) && "pm_mock_capture_temporary_error".equals(scenario));
  }

  private boolean isTimeout(String scenario, String stage) {
    return ("AUTHORIZATION".equals(stage) && "pm_mock_authorization_timeout".equals(scenario))
        || ("CAPTURE".equals(stage) && "pm_mock_capture_timeout".equals(scenario));
  }

  private boolean isTimeoutWithoutPersistence(String scenario, String stage) {
    return ("AUTHORIZATION".equals(stage)
            && "pm_mock_authorization_timeout_not_found".equals(scenario))
        || ("CAPTURE".equals(stage) && "pm_mock_capture_timeout_not_found".equals(scenario));
  }

  private boolean isDecline(String scenario, String stage) {
    return ("AUTHORIZATION".equals(stage)
            && ("pm_mock_decline".equals(scenario)
                || "pm_mock_authorization_decline".equals(scenario)))
        || ("CAPTURE".equals(stage) && "pm_mock_capture_decline".equals(scenario));
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    Response handle() throws Exception;
  }

  private record OperationKey(String stage, UUID requestId) {}

  private record Operation(byte[] requestHash, int status, String responseBody) {}

  private record Request(String stage, UUID requestId, String rawBody, JsonNode body) {}

  private record Response(int status, String body, Duration delay) {}

  private static final class BadRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    private BadRequestException(int status) {
      this.status = status;
    }

    private int status() {
      return status;
    }
  }
}
