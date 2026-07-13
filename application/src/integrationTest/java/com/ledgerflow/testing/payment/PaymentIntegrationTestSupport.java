package com.ledgerflow.testing.payment;

import com.ledgerflow.orders.internal.application.CreateOrderCommand;
import com.ledgerflow.orders.internal.application.OrderService;
import com.ledgerflow.orders.internal.domain.IdempotencyKey;
import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.payments.internal.application.CreatePaymentCommand;
import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.PaymentStore;
import com.ledgerflow.payments.internal.application.PaymentWorkflowService;
import com.ledgerflow.payments.internal.domain.Payment;
import com.ledgerflow.payments.internal.domain.PaymentMoney;
import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class PaymentIntegrationTestSupport extends PostgreSqlIntegrationTest {

  protected static final MockPaymentProviderServer PROVIDER = new MockPaymentProviderServer();

  @Autowired protected OrderService orderService;
  @Autowired protected PaymentWorkflowService paymentWorkflow;
  @Autowired protected PaymentStore paymentStore;
  @Autowired protected PaymentProvider paymentProvider;

  @DynamicPropertySource
  static void paymentProviderProperties(DynamicPropertyRegistry registry) {
    registry.add("ledgerflow.payment.provider.base-url", PROVIDER::baseUrl);
    registry.add("ledgerflow.payment.provider.connect-timeout", () -> "100ms");
    registry.add("ledgerflow.payment.provider.request-timeout", () -> "750ms");
    registry.add("ledgerflow.payment.provider.max-attempts", () -> "2");
    registry.add("ledgerflow.payment.provider.base-backoff", () -> "1ms");
    registry.add("ledgerflow.payment.provider.max-backoff", () -> "2ms");
    registry.add("ledgerflow.payment.provider.backoff-multiplier", () -> "2.0");
    registry.add("ledgerflow.payment.provider.jitter-ratio", () -> "0.0");
  }

  @BeforeEach
  void resetProvider() {
    PROVIDER.reset();
  }

  protected Payment createPayment(String scenario) {
    long amountMinor = 25_990;
    UUID orderId =
        orderService
            .create(
                new CreateOrderCommand(
                    "customer-123",
                    correlationId(),
                    "order-" + UUID.randomUUID(),
                    new Money(amountMinor, "INR"),
                    new IdempotencyKey("order-key-" + UUID.randomUUID())))
            .order()
            .orderId();
    return paymentWorkflow.create(
        new CreatePaymentCommand(
            orderId, new PaymentMoney(amountMinor, "INR"), scenario, UUID.randomUUID()));
  }

  protected Payment authorize(String scenario) {
    Payment payment = createPayment(scenario);
    return paymentWorkflow.authorize(payment.paymentId(), correlationId());
  }

  protected String correlationId() {
    return UUID.randomUUID().toString();
  }

  protected boolean awaitSlowProviderCall() {
    return PROVIDER.awaitSlowCall(Duration.ofSeconds(2));
  }
}
