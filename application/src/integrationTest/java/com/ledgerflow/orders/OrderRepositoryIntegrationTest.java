package com.ledgerflow.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.orders.internal.application.CreateOrderCommand;
import com.ledgerflow.orders.internal.application.CreateOrderResult;
import com.ledgerflow.orders.internal.application.OrderService;
import com.ledgerflow.orders.internal.domain.IdempotencyKey;
import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class OrderRepositoryIntegrationTest extends PostgreSqlIntegrationTest {

  @Autowired OrderService orderService;

  @Test
  void persistsAnInrOrderWithPostgresqlUuidV7AndUtcInstants() {
    CreateOrderResult result =
        orderService.create(
            new CreateOrderCommand(
                "customer-1",
                "repository-test-1",
                "checkout-1",
                new Money(259_900, "INR"),
                new IdempotencyKey("repository-order-0001")));

    assertThat(result.order().orderId().version()).isEqualTo(7);
    assertThat(result.order().currency()).isEqualTo("INR");
    assertThat(result.order().amountMinor()).isEqualTo(259_900);
    assertThat(result.order().createdAt()).isEqualTo(result.order().updatedAt());
    assertThat(result.order().createdAt().toString()).endsWith("Z");
    assertThat(jdbcClient.sql("SELECT count(*) FROM orders").query(Long.class).single()).isOne();
  }

  @Test
  void databaseConstraintsRejectInvalidMoneyAndCurrency() {
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        INSERT INTO orders (
                            owner_subject, amount_minor, currency, status, initial_correlation_id
                        ) VALUES ('customer-1', 0, 'INR', 'CREATED', 'constraint-test')
                        """)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        INSERT INTO orders (
                            owner_subject, amount_minor, currency, status, initial_correlation_id
                        ) VALUES ('customer-1', 100, 'USD', 'CREATED', 'constraint-test')
                        """)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void storesOnlyFixedLengthHashesForIdempotencyInputs() {
    orderService.create(
        new CreateOrderCommand(
            "customer-1",
            "repository-test-2",
            null,
            new Money(100, "INR"),
            new IdempotencyKey("sensitive-client-key-0001")));

    assertThat(
            jdbcClient
                .sql(
                    """
                    SELECT octet_length(key_hash) = 32
                               AND octet_length(request_hash) = 32
                               AND response_body IS NOT NULL
                    FROM idempotency_records
                    """)
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(
            jdbcClient
                .sql(
                    """
                    SELECT count(*)
                    FROM idempotency_records
                    WHERE response_body::text LIKE '%sensitive-client-key-0001%'
                    """)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void databaseRejectsInvalidIdempotencyStateAndCompletionShape() {
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        INSERT INTO idempotency_records (
                            principal_scope, operation, key_hash, request_hash, state
                        ) VALUES (
                            'customer-1', 'CREATE_ORDER_V1', decode('00', 'hex'),
                            decode(repeat('00', 32), 'hex'), 'IN_PROGRESS'
                        )
                        """)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        INSERT INTO idempotency_records (
                            principal_scope, operation, key_hash, request_hash, state
                        ) VALUES (
                            'customer-1', 'CREATE_ORDER_V1', decode(repeat('01', 32), 'hex'),
                            decode(repeat('02', 32), 'hex'), 'BROKEN'
                        )
                        """)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        """
                        INSERT INTO idempotency_records (
                            principal_scope, operation, key_hash, request_hash, state
                        ) VALUES (
                            'customer-1', 'CREATE_ORDER_V1', decode(repeat('03', 32), 'hex'),
                            decode(repeat('04', 32), 'hex'), 'COMPLETED'
                        )
                        """)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
