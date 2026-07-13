package com.ledgerflow.orders.internal.persistence;

import com.ledgerflow.orders.internal.application.IdempotencyRecord;
import com.ledgerflow.orders.internal.application.OrderStore;
import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.orders.internal.domain.Order;
import com.ledgerflow.orders.internal.domain.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderStore implements OrderStore {

  private final JdbcClient jdbcClient;

  public JdbcOrderStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public IdempotencyRecord claimIdempotencyKey(
      String principalScope, String operation, byte[] keyHash, byte[] requestHash) {
    int inserted =
        jdbcClient
            .sql(
                """
                INSERT INTO idempotency_records (
                    principal_scope, operation, key_hash, request_hash, state
                ) VALUES (:principalScope, :operation, :keyHash, :requestHash, 'IN_PROGRESS')
                ON CONFLICT (principal_scope, operation, key_hash) DO NOTHING
                """)
            .param("principalScope", principalScope)
            .param("operation", operation)
            .param("keyHash", keyHash)
            .param("requestHash", requestHash)
            .update();
    if (inserted == 1) {
      return new IdempotencyRecord(true, requestHash, "IN_PROGRESS", null, null, null, null);
    }
    return jdbcClient
        .sql(
            """
            SELECT request_hash, state, resource_id, response_status,
                   response_location, response_body::text AS response_body
            FROM idempotency_records
            WHERE principal_scope = :principalScope
              AND operation = :operation
              AND key_hash = :keyHash
            """)
        .param("principalScope", principalScope)
        .param("operation", operation)
        .param("keyHash", keyHash)
        .query(
            (resultSet, rowNumber) ->
                new IdempotencyRecord(
                    false,
                    resultSet.getBytes("request_hash"),
                    resultSet.getString("state"),
                    resultSet.getObject("resource_id", UUID.class),
                    resultSet.getObject("response_status", Integer.class),
                    resultSet.getString("response_location"),
                    resultSet.getString("response_body")))
        .single();
  }

  @Override
  public Order insertOrder(
      String ownerSubject, String clientReference, Money amount, String correlationId) {
    return jdbcClient
        .sql(
            """
            INSERT INTO orders (
                owner_subject, client_reference, amount_minor, currency, status,
                initial_correlation_id
            ) VALUES (
                :ownerSubject, :clientReference, :amountMinor, :currency, 'CREATED',
                :correlationId
            )
            RETURNING id, owner_subject, client_reference, amount_minor, currency, status,
                      created_at, updated_at
            """)
        .param("ownerSubject", ownerSubject)
        .param("clientReference", clientReference, Types.VARCHAR)
        .param("amountMinor", amount.amountMinor())
        .param("currency", amount.currency())
        .param("correlationId", correlationId)
        .query(this::mapOrder)
        .single();
  }

  @Override
  public void completeIdempotencyKey(
      String principalScope,
      String operation,
      byte[] keyHash,
      UUID resourceId,
      int responseStatus,
      String responseLocation,
      String responseBody) {
    int updated =
        jdbcClient
            .sql(
                """
                UPDATE idempotency_records
                SET state = 'COMPLETED',
                    resource_id = :resourceId,
                    response_status = :responseStatus,
                    response_location = :responseLocation,
                    response_body = CAST(:responseBody AS jsonb),
                    updated_at = statement_timestamp(),
                    completed_at = statement_timestamp()
                WHERE principal_scope = :principalScope
                  AND operation = :operation
                  AND key_hash = :keyHash
                  AND state = 'IN_PROGRESS'
                """)
            .param("resourceId", resourceId)
            .param("responseStatus", responseStatus)
            .param("responseLocation", responseLocation)
            .param("responseBody", responseBody)
            .param("principalScope", principalScope)
            .param("operation", operation)
            .param("keyHash", keyHash)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("Idempotency record was not completed exactly once");
    }
  }

  @Override
  public Optional<Order> findOwnedOrder(UUID orderId, String ownerSubject) {
    return jdbcClient
        .sql(
            """
            SELECT id, owner_subject, client_reference, amount_minor, currency, status,
                   created_at, updated_at
            FROM orders
            WHERE id = :orderId AND owner_subject = :ownerSubject
            """)
        .param("orderId", orderId)
        .param("ownerSubject", ownerSubject)
        .query(this::mapOrder)
        .optional();
  }

  private Order mapOrder(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Order(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("owner_subject"),
        resultSet.getString("client_reference"),
        new Money(resultSet.getLong("amount_minor"), resultSet.getString("currency")),
        OrderStatus.valueOf(resultSet.getString("status")),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }
}
