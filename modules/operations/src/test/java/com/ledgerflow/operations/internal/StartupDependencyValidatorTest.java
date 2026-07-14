package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class StartupDependencyValidatorTest {

  private final OperationsProperties properties =
      new OperationsProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), true);

  @Test
  void validatesDatabaseAndRequiredKafkaTopicsWhenMessagingIsEnabled() {
    DependencyProbe probe = mock(DependencyProbe.class);
    when(probe.kafka(eq(Set.of("main.v1", "main.v1.dlt")))).thenReturn("cluster-id");
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("ledgerflow.messaging.publisher-enabled", "true")
            .withProperty("ledgerflow.messaging.topic", "main.v1")
            .withProperty("ledgerflow.notifications.enabled", "true")
            .withProperty("ledgerflow.notifications.dlt-enabled", "true")
            .withProperty("ledgerflow.notifications.topic", "main.v1")
            .withProperty("ledgerflow.notifications.dead-letter-topic", "main.v1.dlt");

    new StartupDependencyValidator(probe, properties, environment)
        .run(new DefaultApplicationArguments());

    verify(probe).database();
    verify(probe).kafka(Set.of("main.v1", "main.v1.dlt"));
  }

  @Test
  void rejectsFaultInjectionWithoutAnAllowedProfileBeforeCallingDependencies() {
    DependencyProbe probe = mock(DependencyProbe.class);
    MockEnvironment environment =
        new MockEnvironment().withProperty("ledgerflow.fault-injection.enabled", "true");

    StartupDependencyValidator validator =
        new StartupDependencyValidator(probe, properties, environment);

    assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local and test profiles");
    verifyNoInteractions(probe);
  }
}
