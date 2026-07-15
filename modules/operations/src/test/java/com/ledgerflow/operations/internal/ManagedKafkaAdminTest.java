package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;

class ManagedKafkaAdminTest {

  @Test
  void reusesOneClientAndClosesItOnlyOnce() {
    Admin client = mock(Admin.class);
    AtomicInteger creations = new AtomicInteger();
    ManagedKafkaAdmin managed =
        new ManagedKafkaAdmin(
            () -> {
              creations.incrementAndGet();
              return client;
            });

    assertThat(managed.client()).isSameAs(client);
    assertThat(managed.client()).isSameAs(client);
    assertThat(creations).hasValue(1);

    managed.close();
    managed.close();

    verify(client, times(1)).close(Duration.ZERO);
  }
}
