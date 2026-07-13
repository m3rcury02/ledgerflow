package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.testing.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class LedgerFlowApplicationIntegrationTest extends PostgreSqlIntegrationTest {

  @Autowired ApplicationContext applicationContext;

  @Test
  void contextLoadsAgainstPostgreSql() {
    assertThat(applicationContext).isNotNull();
    assertThat(jdbcClient.sql("select 1").query(Integer.class).single()).isOne();
  }
}
