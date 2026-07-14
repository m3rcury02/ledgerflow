package com.ledgerflow.notifications.internal.kafka;

import java.time.Duration;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

final class RetrySequenceBackOff implements BackOff {

  private final long[] intervals;

  RetrySequenceBackOff(Duration... intervals) {
    this.intervals = new long[intervals.length];
    for (int index = 0; index < intervals.length; index++) {
      this.intervals[index] = intervals[index].toMillis();
    }
  }

  @Override
  public BackOffExecution start() {
    return new SequenceExecution();
  }

  private final class SequenceExecution implements BackOffExecution {

    private int index;

    @Override
    public long nextBackOff() {
      if (index >= intervals.length) {
        return STOP;
      }
      return intervals[index++];
    }
  }
}
