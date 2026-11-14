// Every transfer debits ONE shared wallet: worst-case row-lock contention on a single account.
// constant-arrival-rate 200 req/s for 2 minutes.
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';
import { BASE, ADMIN, jsonHeaders, newKey, pick, baseThresholds, summarize, SUMMARY_TREND_STATS } from './lib/common.js';
import { setupData } from './lib/setup.js';

const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION_SEC = parseInt(__ENV.DURATION_SEC || '120', 10);

const transferLatency = new Trend('transfer_latency', true);
const insufficientFunds = new Counter('insufficient_funds');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: SUMMARY_TREND_STATS,
  scenarios: {
    main: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SEC}s`,
      preAllocatedVUs: 200,
      maxVUs: parseInt(__ENV.MAX_VUS || '800', 10),
    },
  },
  thresholds: baseThresholds({ 'transfer_latency': ['p(95)<150'] }),
};

export function setup() {
  return setupData();
}

export default function (data) {
  const hot = data.clients[0];
  let to = pick(data.clients);
  while (to.walletId === hot.walletId) to = pick(data.clients);
  // 200 req/s x 120s x 100 paise = 2.4M paise, well under the hot wallet's 10M.
  const res = http.post(`${BASE}/api/v1/transfers`,
    JSON.stringify({ fromAccountId: hot.walletId, toAccountId: to.walletId, amountPaise: 100, memo: 'k6-hot' }),
    Object.assign(jsonHeaders(ADMIN, newKey('hot')), { tags: { name: 'transfer' } }));
  transferLatency.add(res.timings.duration);
  if (res.status === 422) insufficientFunds.add(1);
}

export function handleSummary(data) {
  return summarize('hot-account', RATE, DURATION_SEC, data);
}
