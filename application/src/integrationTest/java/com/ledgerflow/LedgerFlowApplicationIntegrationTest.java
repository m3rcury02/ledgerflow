package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    properties = {
      "management.logging.export.otlp.enabled=false",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false"
    })
class LedgerFlowApplicationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRESQL =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"));

  @Autowired ApplicationContext applicationContext;

  @Autowired JdbcClient jdbcClient;

  @Test
  void contextLoadsAgainstPostgreSql() {
    assertThat(applicationContext).isNotNull();
    assertThat(jdbcClient.sql("select 1").query(Integer.class).single()).isOne();
  }
}
