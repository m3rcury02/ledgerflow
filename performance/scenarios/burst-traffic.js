import http from "k6/http";
import { check } from "k6";
import { createOrder, uuidv4 } from "./lib/client.js";

// Hypothesis, workload, thresholds: see docs/performance-experiments.md.
// The application enforces a documented per-authenticated-subject write rate limit
// (README.md "Create Order API": 60 creates/minute/subject by default). A burst sized to
// outrun that budget is expected to be rejected with 429, not to destabilize the system or
// return 5xx — so 429 counts as a correctly-handled outcome here, not a failure.
http.setResponseCallback(http.expectedStatuses(200, 201, 429));

// k6's open() only works in the init stage (this top level), not inside default() — see
// lib/client.js's comment. This file's own dedicated identity (provisioned by
// provision-load-test-client.sh as "-burst") keeps this scenario's intentional rate-limit
// exhaustion from bleeding into other scenarios' "clean" throughput measurements.
const BURST_TOKEN = JSON.parse(open("./burst-token.json")).token;

export const options = {
  scenarios: {
    burst: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "5s", target: 30 },
        { duration: "10s", target: 30 },
        { duration: "5s", target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate==0"],
    http_req_duration: ["p(95)<600"],
  },
};

export default function () {
  const key = `burst-${uuidv4()}`;
  const res = createOrder(key, "pm_mock_success", undefined, BURST_TOKEN);
  check(res, {
    "status is 201 or 429 (rate-limited)": (r) => r.status === 201 || r.status === 429,
  });
}
