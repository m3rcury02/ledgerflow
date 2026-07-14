package com.ledgerflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.testing.KafkaIntegrationTest;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

class DependencyToxiproxyIntegrationTest extends KafkaIntegrationTest {

  private static final int DATABASE_PROXY_PORT = 8667;
  private static final int KAFKA_PROXY_PORT = 8668;
  private static ToxiproxyContainer toxiproxy;
  private static Proxy databaseProxy;
  private static Proxy kafkaProxy;

  @BeforeAll
  static void startDependencies() throws Exception {
    toxiproxy =
        new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(integrationNetwork());
    toxiproxy.start();
    ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
    databaseProxy =
        client.createProxy("postgresql", "0.0.0.0:" + DATABASE_PROXY_PORT, "postgres:5432");
    kafkaProxy = client.createProxy("kafka", "0.0.0.0:" + KAFKA_PROXY_PORT, "kafka:9092");
  }

  @AfterAll
  static void stopDependencies() {
    if (toxiproxy != null) {
      toxiproxy.stop();
    }
  }

  @Test
  void temporaryDatabaseUnavailabilityFailsFastAndRecovers() throws Exception {
    databaseProxy.disable();
    try {
      assertThatThrownBy(this::queryDatabase).isInstanceOf(Exception.class);
    } finally {
      databaseProxy.enable();
    }

    assertThat(queryDatabase()).isOne();
  }

  @Test
  void kafkaUnavailabilityIsBoundedAndConnectivityRecovers() throws Exception {
    kafkaProxy.disable();
    try {
      assertThatThrownBy(this::kafkaClusterId).isInstanceOf(Exception.class);
    } finally {
      kafkaProxy.enable();
    }

    assertThat(kafkaClusterId()).isNotBlank();
  }

  private int queryDatabase() throws Exception {
    String jdbcUrl =
        "jdbc:postgresql://"
            + toxiproxy.getHost()
            + ":"
            + toxiproxy.getMappedPort(DATABASE_PROXY_PORT)
            + "/"
            + postgresqlDatabaseName()
            + "?connectTimeout=1&socketTimeout=1";
    try (var connection =
            DriverManager.getConnection(jdbcUrl, postgresqlUsername(), postgresqlPassword());
        var statement = connection.createStatement();
        var result = statement.executeQuery("select 1")) {
      result.next();
      return result.getInt(1);
    }
  }

  private String kafkaClusterId() throws Exception {
    String bootstrap = toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(KAFKA_PROXY_PORT);
    Map<String, Object> properties =
        Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrap,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
            (int) Duration.ofMillis(500).toMillis(),
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
            (int) Duration.ofSeconds(1).toMillis());
    Admin admin = Admin.create(properties);
    try {
      return admin.describeCluster().clusterId().get(1, TimeUnit.SECONDS);
    } finally {
      admin.close(Duration.ZERO);
    }
  }
}
