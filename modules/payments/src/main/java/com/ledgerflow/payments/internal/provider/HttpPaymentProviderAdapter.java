package com.ledgerflow.payments.internal.provider;

import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.ProviderLookupResult;
import com.ledgerflow.payments.internal.application.ProviderResult;
import com.ledgerflow.payments.internal.domain.PaymentStage;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class HttpPaymentProviderAdapter implements PaymentProvider {

  private static final int MAX_RESPONSE_BYTES = 16 * 1024;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI baseUrl;
  private final Duration readTimeout;
  private final Duration overallTimeout;

  public HttpPaymentProviderAdapter(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      URI baseUrl,
      Duration readTimeout,
      Duration overallTimeout) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl;
    this.readTimeout = readTimeout;
    this.overallTimeout = overallTimeout;
  }

  @Override
  public ProviderResult authorize(AuthorizationRequest request) {
    String body =
        write(
            new AuthorizationBody(
                request.paymentId(),
                request.orderId(),
                new MoneyBody(request.amount().amountMinor(), request.amount().currency()),
                request.paymentMethodReference()));
    return invoke(
        "/mock-provider/v1/authorizations",
        request.providerRequestId(),
        request.correlationId(),
        body,
        "AUTHORIZED");
  }

  @Override
  public ProviderResult capture(CaptureRequest request) {
    String body =
        write(
            new CaptureBody(
                request.paymentId(),
                request.orderId(),
                new MoneyBody(request.amount().amountMinor(), request.amount().currency()),
                request.providerAuthorizationId()));
    return invoke(
        "/mock-provider/v1/captures",
        request.providerRequestId(),
        request.correlationId(),
        body,
        "CAPTURED");
  }

  @Override
  public ProviderLookupResult lookup(
      PaymentStage stage, UUID providerRequestId, String correlationId) {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUrl.resolve(
                    "/mock-provider/v1/operations/" + stage.name() + "/" + providerRequestId))
            .timeout(readTimeout)
            .header("X-Correlation-Id", correlationId)
            .GET()
            .build();
    try {
      HttpResponse<String> response = send(request);
      if (response.statusCode() == 404) {
        return new ProviderLookupResult.NotFound();
      }
      if (response.statusCode() == 429 || response.statusCode() >= 500) {
        return new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_LOOKUP_UNAVAILABLE");
      }
      if (response.statusCode() != 200) {
        return new ProviderLookupResult.InvalidResponse("PROVIDER_LOOKUP_INVALID");
      }
      JsonNode body = read(response.body());
      String outcome = requiredText(body, "outcome", 32);
      if ("DECLINED".equals(outcome)) {
        if (!hasExactly(body, "outcome", "failureCode")) {
          return new ProviderLookupResult.InvalidResponse("PROVIDER_LOOKUP_INVALID");
        }
        return new ProviderLookupResult.FoundDecline(requiredText(body, "failureCode", 64));
      }
      if ((stage == PaymentStage.AUTHORIZATION && "AUTHORIZED".equals(outcome))
          || (stage == PaymentStage.CAPTURE && "CAPTURED".equals(outcome))) {
        if (!hasExactly(body, "outcome", "providerReference")) {
          return new ProviderLookupResult.InvalidResponse("PROVIDER_LOOKUP_INVALID");
        }
        return new ProviderLookupResult.FoundSuccess(requiredText(body, "providerReference", 100));
      }
      return new ProviderLookupResult.InvalidResponse("PROVIDER_LOOKUP_CONTRADICTORY");
    } catch (HttpTimeoutException | ConnectException exception) {
      return new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_LOOKUP_UNAVAILABLE");
    } catch (IOException exception) {
      return new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_LOOKUP_UNAVAILABLE");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new ProviderLookupResult.TemporarilyUnavailable("PROVIDER_LOOKUP_INTERRUPTED");
    } catch (IllegalArgumentException exception) {
      return new ProviderLookupResult.InvalidResponse("PROVIDER_LOOKUP_INVALID");
    }
  }

  private ProviderResult invoke(
      String path,
      UUID providerRequestId,
      String correlationId,
      String body,
      String expectedOutcome) {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve(path))
            .timeout(readTimeout)
            .header("Content-Type", "application/json")
            .header("X-Provider-Request-Id", providerRequestId.toString())
            .header("X-Correlation-Id", correlationId)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response = send(request);
      if (response.statusCode() == 429 || response.statusCode() >= 500) {
        return new ProviderResult.TemporaryFailure("PROVIDER_TEMPORARY_FAILURE");
      }
      JsonNode responseBody = read(response.body());
      if (response.statusCode() == 422) {
        if (!hasExactly(responseBody, "outcome", "failureCode")
            || !"DECLINED".equals(requiredText(responseBody, "outcome", 32))) {
          return new ProviderResult.InvalidResponse("PROVIDER_RESPONSE_INVALID");
        }
        return new ProviderResult.Declined(requiredText(responseBody, "failureCode", 64));
      }
      if (response.statusCode() != 200) {
        return new ProviderResult.InvalidResponse("PROVIDER_RESPONSE_INVALID");
      }
      if (!hasExactly(responseBody, "outcome", "providerReference")
          || !expectedOutcome.equals(requiredText(responseBody, "outcome", 32))) {
        return new ProviderResult.InvalidResponse("PROVIDER_RESPONSE_CONTRADICTORY");
      }
      return new ProviderResult.Success(requiredText(responseBody, "providerReference", 100));
    } catch (HttpConnectTimeoutException exception) {
      return new ProviderResult.TemporaryFailure("PROVIDER_CONNECT_FAILURE");
    } catch (HttpTimeoutException exception) {
      return new ProviderResult.Unknown("PROVIDER_TIMEOUT");
    } catch (IOException exception) {
      return new ProviderResult.Unknown("PROVIDER_IO_OUTCOME_UNKNOWN");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new ProviderResult.Unknown("PROVIDER_INTERRUPTED");
    } catch (IllegalArgumentException exception) {
      return new ProviderResult.InvalidResponse("PROVIDER_RESPONSE_INVALID");
    }
  }

  private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> response;
    CompletableFuture<HttpResponse<String>> responseFuture =
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    try {
      response = responseFuture.get(overallTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      responseFuture.cancel(true);
      throw new HttpTimeoutException("Provider overall timeout elapsed");
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IOException("Provider request failed", cause);
    }
    if (response.body().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("provider response exceeds the configured bound");
    }
    return response;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize provider request", exception);
    }
  }

  private JsonNode read(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Could not parse provider response", exception);
    }
  }

  private String requiredText(JsonNode node, String field, int maxLength) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isString()
        || value.asString().isBlank()
        || value.asString().length() > maxLength) {
      throw new IllegalArgumentException("Provider response field is missing: " + field);
    }
    return value.asString();
  }

  private boolean hasExactly(JsonNode node, String first, String second) {
    return node.isObject()
        && node.size() == 2
        && node.get(first) != null
        && node.get(second) != null;
  }

  private record MoneyBody(long amountMinor, String currency) {}

  private record AuthorizationBody(
      UUID paymentId, UUID orderId, MoneyBody amount, String paymentMethodReference) {}

  private record CaptureBody(
      UUID paymentId, UUID orderId, MoneyBody amount, String providerAuthorizationId) {}
}
