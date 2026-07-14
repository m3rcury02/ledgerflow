package com.ledgerflow.orders.internal.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

final class ApiProtectionFilter extends OncePerRequestFilter {

  private static final String ORDER_COLLECTION_PATH = "/api/v1/orders";

  private final long maximumPayloadBytes;
  private final WriteRateLimiter writeRateLimiter;
  private final ProblemDetailsFactory problemDetailsFactory;
  private final ObjectMapper objectMapper;

  ApiProtectionFilter(
      OrderApiSecurityProperties properties,
      WriteRateLimiter writeRateLimiter,
      ProblemDetailsFactory problemDetailsFactory,
      ObjectMapper objectMapper) {
    this.maximumPayloadBytes = properties.maxWritePayload().toBytes();
    this.writeRateLimiter = writeRateLimiter;
    this.problemDetailsFactory = problemDetailsFactory;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !isCreateOrder(request) && !isReadOrder(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getQueryString() != null) {
      writeProblem(
          response,
          problemDetailsFactory.create(
              HttpStatus.BAD_REQUEST,
              "invalid-request",
              "Invalid request",
              "This operation does not accept query parameters.",
              "invalid_request",
              request));
      return;
    }
    if (!isCreateOrder(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    WriteRateLimiter.Decision decision = writeRateLimiter.acquire(rateLimitKey(request));
    if (!decision.allowed()) {
      response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds(decision.retryAfter()));
      writeProblem(
          response,
          problemDetailsFactory.create(
              HttpStatus.TOO_MANY_REQUESTS,
              "rate-limit-exceeded",
              "Too many requests",
              "The write rate limit was exceeded. Retry after the indicated delay.",
              "rate_limit_exceeded",
              request));
      return;
    }
    String contentEncoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
    if (contentEncoding != null
        && !contentEncoding.isBlank()
        && !"identity".equalsIgnoreCase(contentEncoding)) {
      writeProblem(
          response,
          problemDetailsFactory.create(
              HttpStatus.UNSUPPORTED_MEDIA_TYPE,
              "unsupported-media-type",
              "Unsupported media type",
              "Compressed request bodies are not accepted.",
              "unsupported_media_type",
              request));
      return;
    }
    if (request.getContentLengthLong() > maximumPayloadBytes) {
      writePayloadTooLarge(response, request);
      return;
    }
    try {
      filterChain.doFilter(new LimitedBodyRequest(request, maximumPayloadBytes), response);
    } catch (ServletException | IOException exception) {
      if (!response.isCommitted() && causedByPayloadLimit(exception)) {
        writePayloadTooLarge(response, request);
        return;
      }
      throw exception;
    }
  }

  private boolean isCreateOrder(HttpServletRequest request) {
    return HttpMethod.POST.matches(request.getMethod())
        && ORDER_COLLECTION_PATH.equals(request.getRequestURI());
  }

  private boolean isReadOrder(HttpServletRequest request) {
    String itemPrefix = ORDER_COLLECTION_PATH + "/";
    String uri = request.getRequestURI();
    return HttpMethod.GET.matches(request.getMethod())
        && uri.startsWith(itemPrefix)
        && uri.indexOf('/', itemPrefix.length()) == -1;
  }

  private String rateLimitKey(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated()) {
      return "subject:" + authentication.getName();
    }
    return "remote:" + request.getRemoteAddr();
  }

  private String retryAfterSeconds(Duration retryAfter) {
    long millis = retryAfter.toMillis();
    return Long.toString(Math.max(1, Math.ceilDiv(millis, 1000)));
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

  private void writePayloadTooLarge(HttpServletResponse response, HttpServletRequest request)
      throws IOException {
    writeProblem(
        response,
        problemDetailsFactory.create(
            HttpStatus.CONTENT_TOO_LARGE,
            "payload-too-large",
            "Payload too large",
            "The request payload exceeds the configured limit.",
            "payload_too_large",
            request));
  }

  private void writeProblem(HttpServletResponse response, ProblemDetail problem)
      throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
