package com.ledgerflow.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.operations.api.WorkToken;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DrainableWorkTrackerTest {

  @Test
  void shutdownStopsNewWorkAndWaitsForInFlightWorkBeforeCompleting() throws Exception {
    DrainableWorkTracker tracker = new DrainableWorkTracker(Duration.ofSeconds(1));
    WorkToken work = tracker.begin("test-operation");
    CountDownLatch stopped = new CountDownLatch(1);

    tracker.stop(stopped::countDown);

    assertThat(tracker.isAcceptingWork()).isFalse();
    assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(tracker.activeWork()).isOne();

    work.close();

    assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(tracker.isRunning()).isFalse();
    assertThat(tracker.drainTimedOut()).isFalse();
  }

  @Test
  void aBoundedDrainTimeoutIsExposedInsteadOfSilentlyDiscardingWork() throws Exception {
    DrainableWorkTracker tracker = new DrainableWorkTracker(Duration.ofMillis(20));
    WorkToken work = tracker.begin("stuck-operation");
    CountDownLatch stopped = new CountDownLatch(1);

    tracker.stop(stopped::countDown);

    assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(tracker.drainTimedOut()).isTrue();
    assertThat(tracker.activeWork()).isOne();
    work.close();
  }
}
