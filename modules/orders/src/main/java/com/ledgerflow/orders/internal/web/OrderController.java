package com.ledgerflow.orders.internal.web;

import com.ledgerflow.orders.internal.application.CreateOrderCommand;
import com.ledgerflow.orders.internal.application.CreateOrderResult;
import com.ledgerflow.orders.internal.application.OrderService;
import com.ledgerflow.orders.internal.application.OrderView;
import com.ledgerflow.orders.internal.domain.IdempotencyKey;
import com.ledgerflow.orders.internal.domain.Money;
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

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @PostMapping
  public ResponseEntity<OrderResponse> createOrder(
      @RequestHeader("Idempotency-Key") String rawIdempotencyKey,
      @Valid @RequestBody CreateOrderRequest request,
      JwtAuthenticationToken authentication,
      HttpServletRequest servletRequest) {
    String correlationId = correlationId(servletRequest);
    CreateOrderResult result =
        orderService.create(
            new CreateOrderCommand(
                ownerSubject(authentication),
                correlationId,
                request.clientReference(),
                new Money(request.amount().amountMinor(), request.amount().currency()),
                new IdempotencyKey(rawIdempotencyKey)));
    LOGGER.info(
        "Order create completed: orderId={}, replayed={}",
        result.order().orderId(),
        result.replayed());
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(201).header(HttpHeaders.LOCATION, result.location());
    if (result.replayed()) {
      response.header("Idempotency-Replayed", "true");
    }
    return response.body(OrderResponse.from(result.order()));
  }

  @GetMapping("/{orderId}")
  public OrderResponse getOrder(@PathVariable UUID orderId, JwtAuthenticationToken authentication) {
    OrderView order = orderService.get(orderId, ownerSubject(authentication));
    LOGGER.info("Order read completed: orderId={}", orderId);
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
