package com.ledgerflow.operations.internal;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.Map;
import java.util.Set;

final class LedgerFlowMeterFilter implements MeterFilter {

  private static final Map<String, Set<String>> ALLOWLIST =
      Map.of(
          "stage", Set.of("authorization", "capture"),
          "activity", Set.of("call", "lookup"),
          "state",
              Set.of(
                  "closed",
                  "open",
                  "half_open",
                  "due",
                  "leased",
                  "failed",
                  "created",
                  "authorizing",
                  "authorized",
                  "declined",
                  "authorization_retry_pending",
                  "authorization_unknown",
                  "capturing",
                  "capture_confirmed",
                  "capture_accounted",
                  "captured",
                  "capture_declined",
                  "capture_retry_pending",
                  "capture_unknown",
                  "pending",
                  "in_progress",
                  "waiting",
                  "completed"),
          "executor", Set.of("outbox", "notification_retry", "operator_recovery"),
          "operation", Set.of("payment", "outbox", "dead_letter"),
          "outcome",
              Set.of(
                  "created",
                  "completed",
                  "declined",
                  "retry_pending",
                  "failed",
                  "replayed",
                  "idempotency_conflict",
                  "system_failure",
                  "success",
                  "decline",
                  "temporary",
                  "unknown",
                  "invalid",
                  "not_found",
                  "replay",
                  "conflict",
                  "duplicate",
                  "published",
                  "retry",
                  "stale_owner",
                  "applied",
                  "transport_duplicate",
                  "transport_conflict",
                  "semantic_duplicate",
                  "semantic_conflict",
                  "recorded",
                  "persistence_failure",
                  "processed",
                  "dlt",
                  "failure",
                  "ready",
                  "not_ready",
                  "timed_out",
                  "interrupted",
                  "accepted",
                  "idempotent_replay",
                  "already_active",
                  "cooldown",
                  "limit",
                  "waiting",
                  "approved",
                  "used",
                  "rejected"));

  @Override
  public Meter.Id map(Meter.Id id) {
    if (!id.getName().startsWith("ledgerflow.")) {
      return id;
    }
    for (Tag tag : id.getTags()) {
      Set<String> values = ALLOWLIST.get(tag.getKey());
      if (values == null || !values.contains(tag.getValue())) {
        throw new IllegalArgumentException(
            "LedgerFlow metric label is not allowlisted: " + tag.getKey());
      }
    }
    return id;
  }
}
