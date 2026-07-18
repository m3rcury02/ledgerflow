import { check, sleep } from "k6";
import { createOrder, uuidv4 } from "./lib/client.js";

// Hypothesis, workload, thresholds: see docs/performance-experiments.md.
// pm_mock_slow_response makes MockPaymentProviderServer delay its response by 400ms
// (application/src/integrationTest/java/com/ledgerflow/testing/payment/MockPaymentProviderServer.java),
// well inside the application's default provider timeouts, so requests should still
// succeed, just slower. sleep(1) keeps each of the 5 VUs' subjects under the documented
// 60/min per-subject write rate limit (README.md "Create Order API"); without it the
// 400ms provider delay alone paces requests to ~130/min per subject, which would trip the
// limiter and turn this into a rate-limit test instead of a provider-latency test.
export const options = {
  vus: 5,
  duration: "20s",
  thresholds: {
    http_req_failed: ["rate==0"],
    http_req_duration: ["p(95)<1200"],
  },
};

export default function () {
  const key = `slow-${uuidv4()}`;
  const res = createOrder(key, "pm_mock_slow_response");
  check(res, {
    "status is 201": (r) => r.status === 201,
    "order is COMPLETED": (r) => r.json("status") === "COMPLETED",
  });
  sleep(1);
}
