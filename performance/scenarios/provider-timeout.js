import { check, sleep } from "k6";
import { createOrder, uuidv4 } from "./lib/client.js";

// Hypothesis, workload, thresholds: see docs/performance-experiments.md.
// Requires the application to be started with LEDGERFLOW_PAYMENT_PROVIDER_READ_TIMEOUT and
// LEDGERFLOW_PAYMENT_PROVIDER_OVERALL_TIMEOUT set below MockPaymentProviderServer's 1500ms
// TIMEOUT_RESPONSE_DELAY (performance/scripts/run-experiments.sh does this for this
// scenario only); otherwise the mock's delayed-but-successful response never actually
// exceeds the application's default timeouts and this degenerates into slow-provider.js.
// pm_mock_authorization_timeout_not_found never persists the operation on the provider
// side, so a genuine client timeout here must resolve through the lookup-based
// timeout/NOT_FOUND same-ID resend path, not a blind retry that could double-charge.
// Observed (not assumed): the application answers a genuinely ambiguous provider timeout
// with 202 and payment status AUTHORIZATION_UNKNOWN / order status PAYMENT_RETRY_PENDING —
// an explicit "resolution pending" outcome, resolved fast (no blocking retry loop) — rather
// than blocking the HTTP response on retries or guessing an outcome. That fast resolution
// means this scenario needs the same rate-limit-aware sleep(1) as the other "clean"
// throughput scenarios (see slow-provider.js), or its own round-robinned subjects trip the
// documented 60/min per-subject write limit within a few seconds.
export const options = {
  vus: 3,
  duration: "15s",
  thresholds: {
    http_req_failed: ["rate==0"],
  },
};

export default function () {
  const key = `timeout-${uuidv4()}`;
  const res = createOrder(key, "pm_mock_authorization_timeout_not_found");
  check(res, {
    "response is a definitive outcome, not a duplicate-charge signal": (r) =>
      [201, 202, 402, 409, 422, 503].includes(r.status),
  });
  sleep(1);
}
