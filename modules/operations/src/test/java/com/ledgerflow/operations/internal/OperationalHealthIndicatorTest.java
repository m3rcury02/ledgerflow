package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;

class OperationalHealthIndicatorTest {

  @Test
  void readinessIsDownWithoutExposingTheDependencyException() {
    DependencyProbe probe = mock(DependencyProbe.class);
    doThrow(new IllegalStateException("jdbc:postgresql://user:secret@host/database"))
        .when(probe)
        .database();
    DrainableWorkTracker tracker = new DrainableWorkTracker(Duration.ofSeconds(1));

    var health = new OperationalHealthIndicator(probe, tracker, new MockEnvironment()).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("failure", "dependency unavailable");
    assertThat(health.getDetails().toString()).doesNotContain("secret");
  }

  @Test
  void readinessIsDownWhileTheApplicationIsDraining() {
    DependencyProbe probe = mock(DependencyProbe.class);
    DrainableWorkTracker tracker = mock(DrainableWorkTracker.class);
    when(tracker.isAcceptingWork()).thenReturn(false);

    var health = new OperationalHealthIndicator(probe, tracker, new MockEnvironment()).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("acceptingWork", false);
  }
}
