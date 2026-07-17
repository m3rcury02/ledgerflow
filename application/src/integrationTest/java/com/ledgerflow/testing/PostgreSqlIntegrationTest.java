package com.ledgerflow.testing;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("integration-test")
public abstract class PostgreSqlIntegrationTest {

  private static final PostgreSQLContainer POSTGRESQL;
  private static final Network INTEGRATION_NETWORK;

  static {
    INTEGRATION_NETWORK = Network.newNetwork();
    POSTGRESQL =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"))
            .withNetwork(INTEGRATION_NETWORK)
            .withNetworkAliases("postgres");
    POSTGRESQL.start();
  }

  @Autowired protected JdbcClient jdbcClient;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRESQL::getUsername);
    registry.add("spring.datasource.password", POSTGRESQL::getPassword);
  }

  protected static Network integrationNetwork() {
    return INTEGRATION_NETWORK;
  }

  protected static String postgresqlDatabaseName() {
    return POSTGRESQL.getDatabaseName();
  }

  protected static String postgresqlJdbcUrl() {
    return POSTGRESQL.getJdbcUrl();
  }

  protected static String postgresqlUsername() {
    return POSTGRESQL.getUsername();
  }

  protected static String postgresqlPassword() {
    return POSTGRESQL.getPassword();
  }

  @BeforeEach
  void clearOrderData() {
    jdbcClient
        .sql(
            """
            TRUNCATE TABLE
                operator_retry_attempts,
                operator_audit_records,
                operator_break_glass_uses,
                operator_retry_commands,
                operator_break_glass_approvals,
                operator_recovery_state,
                message_replay_audit,
                terminal_dlt_records,
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
