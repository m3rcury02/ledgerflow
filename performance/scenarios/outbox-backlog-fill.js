import { check } from "k6";
import { createOrder, uuidv4 } from "./lib/client.js";

// Hypothesis, workload, thresholds: see docs/performance-experiments.md.
// Generates the backlog for the outbox-backlog-drainage scenario. Run only while the
// application is started with LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED=false so every captured
// payment's outbox_events row stays PENDING; performance/scripts/run-experiments.sh then
// restarts the application with the publisher enabled and times the drain.
export const options = {
  vus: 10,
  iterations: 100,
  thresholds: {
    http_req_failed: ["rate==0"],
  },
};

export default function () {
  const key = `backlog-${uuidv4()}`;
  const res = createOrder(key);
  check(res, {
    "status is 201": (r) => r.status === 201,
  });
}
