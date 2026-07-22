package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseMigrationRunnerIntegrationTest extends PostgreSqlIntegrationTest {

  private static final String TEST_SCHEMA = "migration_runner_test";

  @Test
  void recognizesOnlyTheExplicitMigrationArgument() {
    assertThat(
            DatabaseMigrationRunner.isRequested(
                new String[] {DatabaseMigrationRunner.MIGRATION_ONLY_ARGUMENT}))
        .isTrue();
    assertThat(
            DatabaseMigrationRunner.isRequested(new String[] {"--ledgerflow.migration-only=false"}))
        .isFalse();
    assertThat(DatabaseMigrationRunner.isRequested(new String[0])).isFalse();
  }

  @Test
  void requiresEveryCredentialWithoutDisclosingValues() {
    String marker = "must-not-appear";

    assertThatThrownBy(
            () ->
                DatabaseMigrationRunner.migrate(
                    Map.of("LEDGERFLOW_DB_URL", marker, "LEDGERFLOW_DB_USERNAME", marker)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Missing required migration environment variable: LEDGERFLOW_DB_PASSWORD")
        .hasMessageNotContaining(marker);
  }

  @Test
  void appliesEveryMigrationAndCanBeRerunSafely() {
    jdbcClient.sql("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE").update();
    jdbcClient.sql("CREATE SCHEMA " + TEST_SCHEMA).update();

    String separator = postgresqlJdbcUrl().contains("?") ? "&" : "?";
    Map<String, String> migrationEnvironment =
        Map.of(
            "LEDGERFLOW_DB_URL",
            postgresqlJdbcUrl() + separator + "currentSchema=" + TEST_SCHEMA,
            "LEDGERFLOW_DB_USERNAME",
            postgresqlUsername(),
            "LEDGERFLOW_DB_PASSWORD",
            postgresqlPassword());

    DatabaseMigrationRunner.migrate(migrationEnvironment);
    DatabaseMigrationRunner.migrate(migrationEnvironment);

    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM " + TEST_SCHEMA + ".flyway_schema_history WHERE success")
                .query(Integer.class)
                .single())
        .isEqualTo(9);
  }
}
