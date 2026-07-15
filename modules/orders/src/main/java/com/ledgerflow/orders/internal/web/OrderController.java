package com.ledgerflow.orders.internal.web;

import com.ledgerflow.orders.api.CreateOrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflow;
import com.ledgerflow.orders.api.OrderWorkflowResult;
import com.ledgerflow.orders.api.PublicOrder;
import com.ledgerflow.orders.internal.application.ProviderProtocolException;
import com.ledgerflow.orders.internal.web.OrderHttpModels.CreateOrderRequest;
import com.ledgerflow.orders.internal.web.OrderHttpModels.OrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);

  private final OrderWorkflow orderWorkflow;

  public OrderController(OrderWorkflow orderWorkflow) {
    this.orderWorkflow = orderWorkflow;
  }

  @PostMapping(
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OrderResponse> createOrder(
      @RequestHeader("Idempotency-Key") String rawIdempotencyKey,
      @Valid @RequestBody CreateOrderRequest request,
      JwtAuthenticationToken authentication,
      HttpServletRequest servletRequest) {
    String correlationId = correlationId(servletRequest);
    OrderWorkflowResult result =
        orderWorkflow.create(
            new CreateOrderWorkflow(
                ownerSubject(authentication),
                correlationId,
                request.clientReference(),
                request.amount().amountMinor(),
                request.amount().currency(),
                request.paymentMethodReference(),
                rawIdempotencyKey));
    if (result.responseStatus() == 502) {
      throw new ProviderProtocolException(result.location(), result.replayed());
    }
    LOGGER
        .atInfo()
        .addKeyValue("event_code", "ORDER_HTTP_RESULT")
        .addKeyValue("action", "orders.create")
        .addKeyValue("outcome", result.order().status())
        .addKeyValue("replayed", result.replayed())
        .log("Order HTTP command returned a durable result");
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.responseStatus())
            .header(HttpHeaders.LOCATION, result.location());
    if (result.replayed()) {
      response.header("Idempotency-Replayed", "true");
    }
    return response.body(OrderResponse.from(result.order()));
  }

  @GetMapping(
      value = "/{orderId}",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  public OrderResponse getOrder(@PathVariable UUID orderId, JwtAuthenticationToken authentication) {
    PublicOrder order = orderWorkflow.get(orderId, ownerSubject(authentication));
    LOGGER
        .atInfo()
        .addKeyValue("event_code", "ORDER_READ_RESULT")
        .addKeyValue("action", "orders.read")
        .addKeyValue("outcome", "found")
        .log("Order read completed");
    return OrderResponse.from(order);
  }

  private String correlationId(HttpServletRequest request) {
    return (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
  }

  private String ownerSubject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank() || subject.length() > 200) {
      throw new org.springframework.security.authentication.BadCredentialsException(
          "The bearer token has no valid subject");
    }
    return subject;
  }
}
