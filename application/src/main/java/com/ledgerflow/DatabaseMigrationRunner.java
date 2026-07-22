package com.ledgerflow;

import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** Runs forward-only Flyway migrations without starting the application runtime. */
final class DatabaseMigrationRunner {

  static final String MIGRATION_ONLY_ARGUMENT = "--ledgerflow.migration-only=true";

  private DatabaseMigrationRunner() {
    throw new IllegalStateException("DatabaseMigrationRunner is a static utility");
  }

  static boolean isRequested(String[] arguments) {
    return Arrays.asList(arguments).contains(MIGRATION_ONLY_ARGUMENT);
  }

  static void migrate(Map<String, String> environment) {
    String url = required(environment, "LEDGERFLOW_DB_URL");
    String username = required(environment, "LEDGERFLOW_DB_USERNAME");
    String password = required(environment, "LEDGERFLOW_DB_PASSWORD");

    Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  private static String required(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required migration environment variable: " + name);
    }
    return value;
  }
}
