import { check, sleep } from "k6";
import { createOrder, uuidv4 } from "./lib/client.js";

// Hypothesis, workload, thresholds: see docs/performance-experiments.md.
export const options = {
  vus: 5,
  duration: "20s",
  thresholds: {
    http_req_failed: ["rate==0"],
    http_req_duration: ["p(95)<300"],
  },
};

export default function () {
  const key = `normal-${uuidv4()}`;
  const res = createOrder(key);
  check(res, {
    "status is 201": (r) => r.status === 201,
    "order is COMPLETED": (r) => r.json("status") === "COMPLETED",
  });
  sleep(1);
}
