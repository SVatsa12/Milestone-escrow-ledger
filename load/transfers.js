// Uniform random transfers between the 500 funded wallets, ramping 50 -> 1000 req/s over
// 5 minutes to find the knee. Unique Idempotency-Key per request.
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';
import { BASE, ADMIN, jsonHeaders, newKey, pick, randInt, baseThresholds, summarize, SUMMARY_TREND_STATS } from './lib/common.js';
import { setupData } from './lib/setup.js';

const START = parseInt(__ENV.START_RPS || '50', 10);
const TARGET = parseInt(__ENV.TARGET_RPS || '1000', 10);
const DURATION_SEC = parseInt(__ENV.DURATION_SEC || '300', 10);

const transferLatency = new Trend('transfer_latency', true);
const insufficientFunds = new Counter('insufficient_funds');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: SUMMARY_TREND_STATS,
  scenarios: {
    main: {
      executor: 'ramping-arrival-rate',
      startRate: START,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: parseInt(__ENV.MAX_VUS || '1000', 10),
      stages: [{ target: TARGET, duration: `${DURATION_SEC}s` }],
    },
  },
  // p95 < 150ms is the bar at the *sustainable* rate; this run deliberately goes past the knee,
  // so it is expected to fail here. See load/README.md for how the bar was tuned.
  thresholds: baseThresholds({ 'transfer_latency': ['p(95)<150'] }),
};

export function setup() {
  return setupData();
}

export default function (data) {
  const from = pick(data.clients);
  let to = pick(data.clients);
  while (to.walletId === from.walletId) to = pick(data.clients);
  const res = http.post(`${BASE}/api/v1/transfers`,
    JSON.stringify({ fromAccountId: from.walletId, toAccountId: to.walletId, amountPaise: randInt(100, 5000), memo: 'k6' }),
    Object.assign(jsonHeaders(ADMIN, newKey('tr')), { tags: { name: 'transfer' } }));
  transferLatency.add(res.timings.duration);
  if (res.status === 422) insufficientFunds.add(1);
}

export function handleSummary(data) {
  return summarize('transfers', `${START}->${TARGET}`, DURATION_SEC, data);
}
