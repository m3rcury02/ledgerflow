import { check, sleep } from "k6";
import { createOrder, uuidv4 } from "./lib/client.js";

// Hypothesis, workload, thresholds: see docs/performance-experiments.md.
// Every iteration uses a brand-new Idempotency-Key and clientReference, so
// idempotency_records/orders/payments/ledger rows grow monotonically for the whole run.
// Each of the 8 VUs gets its own subject from the token pool (lib/client.js), and
// sleep(1.5) keeps each subject under the documented 60/min per-subject write rate limit
// (README.md "Create Order API") — this scenario is about row-count growth, not the rate
// limiter, which burst-traffic.js already exercises directly.
// performance/scripts/run-experiments.sh compares p95 latency in the first third of this
// run against the last third (from the k6 summary trend) to check for growth-driven decay,
// and records the resulting row counts directly from PostgreSQL.
export const options = {
  vus: 8,
  duration: "45s",
  thresholds: {
    http_req_failed: ["rate==0"],
  },
};

export default function () {
  const key = `growth-${uuidv4()}`;
  const res = createOrder(key);
  check(res, {
    "status is 201": (r) => r.status === 201,
  });
  sleep(1.5);
}
