package com.ledgerflow.operations.internal;

import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
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

  DrainableWorkTracker(Duration drainTimeout) {
    this.drainTimeout = drainTimeout;
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
          LOGGER.error(
              "Graceful shutdown timed out with {} in-flight operations still active", activeWork);
          break;
        }
        try {
          long millis = Math.max(1L, Duration.ofNanos(remaining).toMillis());
          monitor.wait(millis);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          drainTimedOut = true;
          LOGGER.error("Graceful shutdown drain was interrupted", exception);
          break;
        }
      }
      running = false;
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
}
