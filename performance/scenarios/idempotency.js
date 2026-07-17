import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  scenarios: {
    contested_idempotency: {
      executor: 'per-vu-iterations',
      vus: 10,
      iterations: 5,
      maxDuration: '10s',
    }
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'], 
  },
};

const BASE_URL = __ENV.API_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

// The key and payload are identical for all VUs in this execution to simulate contention
const SHARED_KEY = `perf-idem-${uuidv4()}`;
const PAYLOAD = JSON.stringify({
  amount: {
    value: 5000,
    currency: "USD"
  },
  referenceId: SHARED_KEY
});

export default function () {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Idempotency-Key': SHARED_KEY,
      'Authorization': `Bearer ${TOKEN}`
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/orders`, PAYLOAD, params);
  
  // Either the first creates it (201) or it's a reuse (200 OK or 409 Conflict depending on app design)
  // Our app usually returns 200 or 201 for idempotency reuse. If security blocks, 401.
  check(res, {
    'status is 2xx or 401': (r) => r.status === 200 || r.status === 201 || r.status === 401,
  });
}
