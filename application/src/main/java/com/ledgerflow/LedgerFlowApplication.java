package com.ledgerflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LedgerFlowApplication {

  public static void main(String[] args) {
    if (DatabaseMigrationRunner.isRequested(args)) {
      DatabaseMigrationRunner.migrate(System.getenv());
      return;
    }
    SpringApplication.run(LedgerFlowApplication.class, args);
  }
}
