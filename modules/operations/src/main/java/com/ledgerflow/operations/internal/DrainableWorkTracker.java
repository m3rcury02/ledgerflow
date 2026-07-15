package com.ledgerflow.operations.internal;

import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

final class DrainableWorkTracker implements WorkTracker, SmartLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(DrainableWorkTracker.class);

  private final Object monitor = new Object();
  private final Duration drainTimeout;
  private volatile boolean running = true;
  private volatile boolean acceptingWork = true;
  private volatile boolean drainTimedOut;
  private int activeWork;
  private final Counter completedDrains;
  private final Counter timedOutDrains;
  private final Counter interruptedDrains;

  DrainableWorkTracker(Duration drainTimeout) {
    this(drainTimeout, new SimpleMeterRegistry());
  }

  DrainableWorkTracker(Duration drainTimeout, MeterRegistry meterRegistry) {
    this.drainTimeout = drainTimeout;
    completedDrains = drainCounter(meterRegistry, "completed");
    timedOutDrains = drainCounter(meterRegistry, "timed_out");
    interruptedDrains = drainCounter(meterRegistry, "interrupted");
    Gauge.builder("ledgerflow.graceful.drain.active", this, DrainableWorkTracker::activeWork)
        .description("Currently tracked in-flight external work")
        .register(meterRegistry);
    Gauge.builder(
            "ledgerflow.graceful.drain.accepting",
            this,
            tracker -> tracker.isAcceptingWork() ? 1.0 : 0.0)
        .description("Whether new tracked work is admitted")
        .register(meterRegistry);
  }

  @Override
  public WorkToken begin(String operation) {
    if (operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("operation name must not be blank");
    }
    synchronized (monitor) {
      if (!acceptingWork) {
        throw new IllegalStateException("Application is draining and cannot accept " + operation);
      }
      activeWork++;
    }
    AtomicBoolean closed = new AtomicBoolean();
    return () -> {
      if (closed.compareAndSet(false, true)) {
        synchronized (monitor) {
          activeWork--;
          monitor.notifyAll();
        }
      }
    };
  }

  @Override
  public boolean isAcceptingWork() {
    return acceptingWork;
  }

  boolean drainTimedOut() {
    return drainTimedOut;
  }

  int activeWork() {
    synchronized (monitor) {
      return activeWork;
    }
  }

  @Override
  public void start() {
    running = true;
    acceptingWork = true;
  }

  @Override
  public void stop(Runnable callback) {
    acceptingWork = false;
    Thread.ofVirtual()
        .name("ledgerflow-work-drain")
        .start(
            () -> {
              drain(callback);
            });
  }

  @Override
  public void stop() {
    acceptingWork = false;
    drain(this::noOp);
  }

  private void noOp() {
    LOGGER.trace("Synchronous work drain completed");
  }

  private void drain(Runnable callback) {
    long deadline = System.nanoTime() + drainTimeout.toNanos();
    synchronized (monitor) {
      while (activeWork > 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          drainTimedOut = true;
          timedOutDrains.increment();
          LOGGER
              .atError()
              .addKeyValue("event_code", "GRACEFUL_DRAIN_TIMED_OUT")
              .addKeyValue("action", "application.shutdown.drain")
              .addKeyValue("error_code", "DRAIN_TIMEOUT")
              .addKeyValue("active_work", activeWork)
              .log("Graceful shutdown timed out with in-flight work");
          break;
        }
        try {
          long millis = Math.max(1L, Duration.ofNanos(remaining).toMillis());
          monitor.wait(millis);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          drainTimedOut = true;
          interruptedDrains.increment();
          LOGGER
              .atError()
              .addKeyValue("event_code", "GRACEFUL_DRAIN_INTERRUPTED")
              .addKeyValue("action", "application.shutdown.drain")
              .addKeyValue("error_code", "DRAIN_INTERRUPTED")
              .log("Graceful shutdown drain was interrupted");
          break;
        }
      }
      running = false;
      if (!drainTimedOut) {
        completedDrains.increment();
      }
    }
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MIN_VALUE;
  }

  private Counter drainCounter(MeterRegistry meterRegistry, String outcome) {
    return Counter.builder("ledgerflow.graceful.drain.results")
        .description("Graceful drain completions by bounded outcome")
        .tag("outcome", outcome)
        .register(meterRegistry);
  }
}
