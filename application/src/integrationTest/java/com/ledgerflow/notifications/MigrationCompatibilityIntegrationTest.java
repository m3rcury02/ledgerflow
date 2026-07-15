package com.ledgerflow.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class MigrationCompatibilityIntegrationTest extends PostgreSqlIntegrationTest {

  @Test
  void upgradesRepresentativeV5NotificationEvidenceWithoutDeletingIt() {
    String schema = schemaName("compatible");
    try {
      DataSource dataSource = migrateToV5(schema);
      LegacyFixture fixture = seedLegacyNotification(dataSource, false);

      migrateToLatest(schema);

      JdbcClient legacy = JdbcClient.create(dataSource);
      assertThat(
              legacy
                  .sql(
                      """
                      SELECT effect_type, effect_identity_version, effect_key,
                             source_causation_id, source_occurred_at
                      FROM notifications WHERE event_id = :eventId
                      """)
                  .param("eventId", fixture.eventId())
                  .query(
                      (resultSet, rowNumber) ->
                          new MigratedNotification(
                              resultSet.getString("effect_type"),
                              resultSet.getInt("effect_identity_version"),
                              resultSet.getObject("effect_key", UUID.class),
                              resultSet.getObject("source_causation_id", UUID.class),
                              resultSet
                                  .getObject("source_occurred_at", OffsetDateTime.class)
                                  .toInstant()))
                  .single())
          .isEqualTo(
              new MigratedNotification(
                  "PAYMENT_CAPTURED_NOTIFICATION",
                  1,
                  fixture.ledgerTransactionId(),
                  fixture.causationId(),
                  fixture.occurredAt().toInstant()));
      assertThat(
              legacy
                  .sql(
                      "SELECT processing_outcome FROM notification_inbox WHERE event_id = :eventId")
                  .param("eventId", fixture.eventId())
                  .query(String.class)
                  .single())
          .isEqualTo("APPLIED");
      assertThat(legacy.sql("SELECT count(*) FROM notifications").query(Long.class).single())
          .isOne();
      assertThat(legacy.sql("SELECT count(*) FROM terminal_dlt_records").query(Long.class).single())
          .isZero();
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void failsClosedWhenV5ContainsDuplicateSemanticEffects() {
    String schema = schemaName("conflict");
    try {
      DataSource dataSource = migrateToV5(schema);
      seedLegacyNotification(dataSource, true);

      assertThatThrownBy(() -> migrateToLatest(schema)).isInstanceOf(FlywayException.class);

      JdbcClient legacy = JdbcClient.create(dataSource);
      assertThat(legacy.sql("SELECT count(*) FROM notifications").query(Long.class).single())
          .isEqualTo(2);
      assertThat(
              legacy
                  .sql(
                      """
                      SELECT count(*) FROM information_schema.columns
                      WHERE table_schema = :schema
                        AND table_name = 'notifications'
                        AND column_name = 'effect_key'
                      """)
                  .param("schema", schema)
                  .query(Long.class)
                  .single())
          .isZero();
    } finally {
      dropSchema(schema);
    }
  }

  private DataSource migrateToV5(String schema) {
    migrate(schema, MigrationVersion.fromVersion("5"));
    return schemaDataSource(schema);
  }

  private void migrateToLatest(String schema) {
    migrate(schema, MigrationVersion.LATEST);
  }

  private void migrate(String schema, MigrationVersion target) {
    Flyway.configure()
        .dataSource(postgresqlJdbcUrl(), postgresqlUsername(), postgresqlPassword())
        .defaultSchema(schema)
        .schemas(schema)
        .createSchemas(true)
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private LegacyFixture seedLegacyNotification(DataSource dataSource, boolean duplicateEffect) {
    LegacyFixture fixture = LegacyFixture.create();
    JdbcClient legacy = JdbcClient.create(dataSource);
    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        .executeWithoutResult(
            status -> {
              insertBusinessEvidence(legacy, fixture);
              insertTransportAndNotification(legacy, fixture, fixture.eventId(), 10L);
              if (duplicateEffect) {
                insertTransportAndNotification(legacy, fixture, UUID.randomUUID(), 11L);
              }
            });
    return fixture;
  }

  private void insertBusinessEvidence(JdbcClient legacy, LegacyFixture fixture) {
    legacy
        .sql(
            """
            INSERT INTO orders (
                id, owner_subject, amount_minor, currency, status, initial_correlation_id,
                created_at, updated_at
            ) VALUES (
                :orderId, 'legacy-customer', 2599, 'INR', 'CREATED', 'legacy-correlation',
                :occurredAt, :occurredAt
            )
            """)
        .param("orderId", fixture.orderId())
        .param("occurredAt", fixture.occurredAt())
        .update();
    legacy
        .sql(
            """
            INSERT INTO payments (
                id, order_id, amount_minor, currency, state, authorization_request_id,
                capture_request_id, provider_authorization_id, provider_capture_id,
                authorization_attempt_count, capture_attempt_count, created_at, updated_at
            ) VALUES (
                :paymentId, :orderId, 2599, 'INR', 'CAPTURE_ACCOUNTED',
                :authorizationRequestId, :causationId, 'legacy-authorization', 'legacy-capture',
                1, 1, :occurredAt, :occurredAt
            )
            """)
        .param("paymentId", fixture.paymentId())
        .param("orderId", fixture.orderId())
        .param("authorizationRequestId", UUID.randomUUID())
        .param("causationId", fixture.causationId())
        .param("occurredAt", fixture.occurredAt())
        .update();
    legacy
        .sql(
            """
            INSERT INTO ledger_transactions (
                id, journal_type, source_type, source_id, payment_id, order_id, currency,
                description, correlation_id, created_by, posted_at
            ) VALUES (
                :ledgerId, 'PAYMENT_CAPTURE', 'PAYMENT_CAPTURE', :paymentId,
                :paymentId, :orderId, 'INR', 'Legacy captured payment',
                'legacy-correlation', 'migration-test', :occurredAt
            )
            """)
        .param("ledgerId", fixture.ledgerTransactionId())
        .param("paymentId", fixture.paymentId())
        .param("orderId", fixture.orderId())
        .param("occurredAt", fixture.occurredAt())
        .update();
    legacy
        .sql(
            """
            INSERT INTO ledger_entries (
                transaction_id, account_id, side, amount_minor, currency, created_at
            ) VALUES
                (:ledgerId, '00000000-0000-4000-8000-000000000001', 'D', 2599, 'INR', :occurredAt),
                (:ledgerId, '00000000-0000-4000-8000-000000000002', 'C', 2599, 'INR', :occurredAt)
            """)
        .param("ledgerId", fixture.ledgerTransactionId())
        .param("occurredAt", fixture.occurredAt())
        .update();
  }

  private void insertTransportAndNotification(
      JdbcClient legacy, LegacyFixture fixture, UUID eventId, long offset) {
    String payload =
        """
        {
          "eventId":"%s",
          "eventType":"com.ledgerflow.payment.captured",
          "schemaVersion":1,
          "aggregateId":"%s",
          "correlationId":"legacy-correlation",
          "causationId":"%s",
          "occurredAt":"%s",
          "data":{
            "orderId":"%s",
            "paymentId":"%s",
            "ledgerTransactionId":"%s",
            "amountMinor":2599,
            "currency":"INR",
            "capturedAt":"%s"
          }
        }
        """
            .formatted(
                eventId,
                fixture.paymentId(),
                fixture.causationId(),
                fixture.occurredAt().toInstant(),
                fixture.orderId(),
                fixture.paymentId(),
                fixture.ledgerTransactionId(),
                fixture.occurredAt().toInstant());
    legacy
        .sql(
            """
            INSERT INTO outbox_events (
                event_id, deduplication_key, aggregate_type, aggregate_id, event_type,
                schema_version, topic, event_key, payload, payload_hash, correlation_id,
                causation_id, occurred_at, created_at
            ) VALUES (
                :eventId, :deduplicationKey, 'payment', :paymentId,
                'com.ledgerflow.payment.captured', 1, 'ledgerflow.payment-captured.v1',
                :eventKey, CAST(:payload AS jsonb), decode(repeat('11', 32), 'hex'),
                'legacy-correlation', :causationId, :occurredAt, :occurredAt
            )
            """)
        .param("eventId", eventId)
        .param("deduplicationKey", "legacy:" + eventId)
        .param("paymentId", fixture.paymentId())
        .param("eventKey", fixture.orderId().toString())
        .param("payload", payload)
        .param("causationId", fixture.causationId())
        .param("occurredAt", fixture.occurredAt())
        .update();
    legacy
        .sql(
            """
            INSERT INTO notification_inbox (
                event_id, event_type, schema_version, topic, partition_id, offset_value,
                payload_hash, received_at, processed_at
            ) VALUES (
                :eventId, 'com.ledgerflow.payment.captured', 1,
                'ledgerflow.payment-captured.v1', 0, :offset,
                decode(repeat('22', 32), 'hex'), :occurredAt, :occurredAt
            )
            """)
        .param("eventId", eventId)
        .param("offset", offset)
        .param("occurredAt", fixture.occurredAt())
        .update();
    legacy
        .sql(
            """
            INSERT INTO notifications (
                event_id, order_id, payment_id, type, status, amount_minor, currency,
                business_correlation_id, processing_correlation_id, created_at
            ) VALUES (
                :eventId, :orderId, :paymentId, 'PAYMENT_CAPTURED', 'CREATED', 2599, 'INR',
                'legacy-correlation', 'legacy-processing', :occurredAt
            )
            """)
        .param("eventId", eventId)
        .param("orderId", fixture.orderId())
        .param("paymentId", fixture.paymentId())
        .param("occurredAt", fixture.occurredAt())
        .update();
  }

  private DataSource schemaDataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String separator = postgresqlJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgresqlJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgresqlUsername());
    dataSource.setPassword(postgresqlPassword());
    return dataSource;
  }

  private String schemaName(String suffix) {
    return "m5d_" + suffix + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  private void dropSchema(String schema) {
    jdbcClient.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
  }

  private record LegacyFixture(
      UUID orderId,
      UUID paymentId,
      UUID ledgerTransactionId,
      UUID eventId,
      UUID causationId,
      OffsetDateTime occurredAt) {

    private static LegacyFixture create() {
      return new LegacyFixture(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          OffsetDateTime.parse("2026-07-13T15:00:00Z"));
    }
  }

  private record MigratedNotification(
      String effectType,
      int effectVersion,
      UUID effectKey,
      UUID sourceCausationId,
      java.time.Instant sourceOccurredAt) {}
}
