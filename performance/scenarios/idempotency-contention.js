import { check } from "k6";
import { createOrder } from "./lib/client.js";

// Hypothesis, workload, thresholds: see docs/performance-experiments.md.
// Every VU/iteration sends the exact same Idempotency-Key and body, concurrently, and the
// response must always be the same order — never a duplicate payment.
export const options = {
  vus: 20,
  iterations: 20,
  thresholds: {
    http_req_failed: ["rate==0"],
  },
};

// k6's open() only works in the init stage (this top level), not inside default() — see
// lib/client.js's comment. performance/scripts/run-experiments.sh generates the key and
// client reference (not this script) so it can run the authoritative "exactly one order"
// database check against the same values after this script exits — concurrent VUs have no
// way to share mutable state with each other or with the shell. This file's own dedicated
// identity (provisioned by provision-load-test-client.sh as "-contention") is required
// because idempotency is scoped per subject: a round-robin pool would give each VU its own
// idempotency namespace and never actually contend.
const CONTENTION = JSON.parse(open("./contention-token.json"));

export default function () {
  const res = createOrder(CONTENTION.key, "pm_mock_success", CONTENTION.clientReference, CONTENTION.token);
  check(res, {
    "status is 200 or 201": (r) => r.status === 200 || r.status === 201,
    "orderId is present": (r) => !!r.json("orderId"),
  });
}

// Authoritative duplicate-processing proof (exactly one order/payment row for
// CONTENTION.clientReference) is a database check run by
// performance/scripts/run-experiments.sh after this script exits, not a k6-side check.
