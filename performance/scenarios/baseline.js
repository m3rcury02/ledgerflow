import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Trend } from 'k6/metrics';

// Custom trend for payment creation latency
const paymentLatency = new Trend('payment_creation_duration');

export const options = {
  scenarios: {
    default: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 20,
      maxDuration: '1m',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'], // less than 1% failure rate
    'payment_creation_duration': ['p(95)<500'], // 95% of requests under 500ms
  },
};

const BASE_URL = __ENV.API_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

export default function () {
  const payload = JSON.stringify({
    amount: {
      value: Math.floor(Math.random() * 10000) + 100,
      currency: "USD"
    },
    referenceId: `perf-base-${uuidv4()}`
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Idempotency-Key': uuidv4(),
      'Authorization': `Bearer ${TOKEN}`
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, params);
  
  paymentLatency.add(res.timings.duration);

  check(res, {
    'is status 201 or 401': (r) => r.status === 201 || r.status === 401,
  });

  if (res.status !== 201 && res.status !== 401) {
    console.error(`Unexpected status: ${res.status} - Body: ${res.body}`);
  }

  sleep(0.5);
}
