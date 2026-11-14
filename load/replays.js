// 30% of requests resend an earlier Idempotency-Key with the identical body (a client retry).
// Those must come back with the stored first response and Idempotent-Replayed: true.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { BASE, ADMIN, jsonHeaders, newKey, pick, randInt, baseThresholds, summarize, SUMMARY_TREND_STATS } from './lib/common.js';
import { setupData } from './lib/setup.js';

const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION_SEC = parseInt(__ENV.DURATION_SEC || '120', 10);
const REPLAY_SHARE = 0.3;

const transferLatency = new Trend('transfer_latency', true);
const insufficientFunds = new Counter('insufficient_funds');
const replayed = new Counter('replayed');
const replayHonoured = new Rate('replay_honoured');

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
  thresholds: baseThresholds({
    'transfer_latency': ['p(95)<150'],
    // Every resend we expected to replay must actually have been replayed.
    'replay_honoured': ['rate==1'],
  }),
};

export function setup() {
  return setupData();
}

// Per-VU memory of requests already sent, with the entryId the first response returned.
const sent = [];

export default function (data) {
  if (sent.length > 0 && Math.random() < REPLAY_SHARE) {
    const prev = pick(sent);
    const res = post(prev.key, prev.body);
    const wasReplay = res.headers['Idempotent-Replayed'] === 'true';
    if (wasReplay) replayed.add(1);
    const ok = wasReplay && res.status === prev.status && res.json('entryId') === prev.entryId;
    replayHonoured.add(ok);
    check(res, { 'replay returns the stored first response': () => ok });
    return;
  }
  const from = pick(data.clients);
  let to = pick(data.clients);
  while (to.walletId === from.walletId) to = pick(data.clients);
  const key = newKey('rp');
  const body = JSON.stringify({ fromAccountId: from.walletId, toAccountId: to.walletId, amountPaise: randInt(100, 5000), memo: 'k6-replay' });
  const res = post(key, body);
  if (res.status === 201) {
    sent.push({ key, body, status: res.status, entryId: res.json('entryId') });
    if (sent.length > 500) sent.shift();
  }
}

function post(key, body) {
  const res = http.post(`${BASE}/api/v1/transfers`, body,
    Object.assign(jsonHeaders(ADMIN, key), { tags: { name: 'transfer' } }));
  transferLatency.add(res.timings.duration);
  if (res.status === 422) insufficientFunds.add(1);
  return res;
}

export function handleSummary(data) {
  return summarize('replays', RATE, DURATION_SEC, data);
}
