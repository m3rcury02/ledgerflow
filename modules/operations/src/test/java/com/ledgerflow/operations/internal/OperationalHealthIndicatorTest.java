package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;

class OperationalHealthIndicatorTest {

  @Test
  void readinessIsDownWithoutExposingTheDependencyException() {
    ReadinessProbeCache readiness = mock(ReadinessProbeCache.class);
    when(readiness.readiness(false)).thenReturn(new ReadinessProbeCache.DependencyReadiness(false));
    DrainableWorkTracker tracker = new DrainableWorkTracker(Duration.ofSeconds(1));

    var health = new OperationalHealthIndicator(readiness, tracker, new MockEnvironment()).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).isEmpty();
  }

  @Test
  void readinessIsDownWhileTheApplicationIsDraining() {
    ReadinessProbeCache readiness = mock(ReadinessProbeCache.class);
    when(readiness.readiness(false)).thenReturn(new ReadinessProbeCache.DependencyReadiness(true));
    DrainableWorkTracker tracker = mock(DrainableWorkTracker.class);
    when(tracker.isAcceptingWork()).thenReturn(false);

    var health = new OperationalHealthIndicator(readiness, tracker, new MockEnvironment()).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).isEmpty();
  }
}
