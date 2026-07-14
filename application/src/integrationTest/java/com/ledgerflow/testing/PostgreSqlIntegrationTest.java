package com.ledgerflow.testing;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("integration-test")
public abstract class PostgreSqlIntegrationTest {

  private static final PostgreSQLContainer POSTGRESQL;

  static {
    POSTGRESQL = new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"));
    POSTGRESQL.start();
  }

  @Autowired protected JdbcClient jdbcClient;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRESQL::getUsername);
    registry.add("spring.datasource.password", POSTGRESQL::getPassword);
  }

  @BeforeEach
  void clearOrderData() {
    jdbcClient
        .sql(
            """
            TRUNCATE TABLE
                message_replay_audit,
                dead_letter_records,
                notifications,
                notification_inbox,
                outbox_events,
                ledger_entries,
                ledger_transactions,
                payment_attempt_history,
                payments,
                idempotency_records,
                orders
            """)
        .update();
  }
}
