package com.ledgerflow.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.orders.internal.application.CreateOrderCommand;
import com.ledgerflow.orders.internal.application.CreateOrderResult;
import com.ledgerflow.orders.internal.application.IdempotencyConflictException;
import com.ledgerflow.orders.internal.application.OrderService;
import com.ledgerflow.orders.internal.domain.IdempotencyKey;
import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderIdempotencyIntegrationTest extends PostgreSqlIntegrationTest {

  @Autowired OrderService orderService;

  @Test
  void concurrentIdenticalRequestsCreateExactlyOneOrder() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<CreateOrderResult> first = executor.submit(() -> createAfter(ready, start));
      Future<CreateOrderResult> second = executor.submit(() -> createAfter(ready, start));

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      CreateOrderResult firstResult = first.get(30, TimeUnit.SECONDS);
      CreateOrderResult secondResult = second.get(30, TimeUnit.SECONDS);

      assertThat(firstResult.order().orderId()).isEqualTo(secondResult.order().orderId());
      assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(jdbcClient.sql("SELECT count(*) FROM orders").query(Long.class).single()).isOne();
      assertThat(
              jdbcClient.sql("SELECT count(*) FROM idempotency_records").query(Long.class).single())
          .isOne();
    }
  }

  @Test
  void rejectsTheSameKeyForADifferentCanonicalPayload() {
    orderService.create(command(10_000));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.create(command(10_001)))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(jdbcClient.sql("SELECT count(*) FROM orders").query(Long.class).single()).isOne();
  }

  private CreateOrderResult createAfter(CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent test did not start in time");
    }
    return orderService.create(command(10_000));
  }

  private CreateOrderCommand command(long amountMinor) {
    return new CreateOrderCommand(
        "customer-concurrent",
        "concurrency-test",
        "checkout-concurrent",
        new Money(amountMinor, "INR"),
        new IdempotencyKey("concurrent-order-key-0001"));
  }
}
