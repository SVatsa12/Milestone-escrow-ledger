// Realistic mix: 60% reads (project or balance), 20% transfers, 10% approve, 5% dispute,
// 5% payout. Also the load generator for chaos/kill-under-load.sh (K2), which sets
// DURATION_SEC and ACK_LOG to record every acknowledged Idempotency-Key.
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';
import { BASE, ADMIN, actor, jsonHeaders, newKey, pick, randInt, baseThresholds, summarize, SUMMARY_TREND_STATS } from './lib/common.js';
import { setupData } from './lib/setup.js';

const RATE = parseInt(__ENV.RATE || '150', 10);
const DURATION_SEC = parseInt(__ENV.DURATION_SEC || '120', 10);
const ACK_LOG = __ENV.ACK_LOG === '1';

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
      preAllocatedVUs: 150,
      maxVUs: parseInt(__ENV.MAX_VUS || '600', 10),
    },
  },
  thresholds: baseThresholds({ 'transfer_latency': ['p(95)<150'] }),
};

export function setup() {
  return setupData();
}

export default function (data) {
  const roll = Math.random();
  if (roll < 0.6) return read(data);
  if (roll < 0.8) return transfer(data);
  if (roll < 0.9) return approve(data);
  if (roll < 0.95) return dispute(data);
  return payout(data);
}

function read(data) {
  if (Math.random() < 0.5) {
    http.get(`${BASE}/api/escrow/projects/${pick(data.projects).projectId}`, { tags: { name: 'get-project' } });
  } else {
    http.get(`${BASE}/api/v1/accounts/${pick(data.clients).walletId}`, { tags: { name: 'get-balance' } });
  }
}

function transfer(data) {
  const from = pick(data.clients);
  let to = pick(data.clients);
  while (to.walletId === from.walletId) to = pick(data.clients);
  const key = newKey('mx-tr');
  const res = http.post(`${BASE}/api/v1/transfers`,
    JSON.stringify({ fromAccountId: from.walletId, toAccountId: to.walletId, amountPaise: randInt(100, 5000), memo: 'k6-mixed' }),
    Object.assign(jsonHeaders(ADMIN, key), { tags: { name: 'transfer' } }));
  transferLatency.add(res.timings.duration);
  if (res.status === 422) insufficientFunds.add(1);
  ack('transfer', key, res);
}

// Approve / dispute pick a random submitted milestone; once a milestone has moved on, later
// attempts get 409 INVALID_STATE_TRANSITION, which is an expected answer, not an error.
function approve(data) {
  const p = pick(data.projects);
  const key = newKey('mx-ap');
  const res = http.post(`${BASE}/api/escrow/milestones/${pick(p.milestoneIds)}/approve`, null,
    Object.assign(jsonHeaders(actor(p.clientId, 'CLIENT'), key), { tags: { name: 'approve' } }));
  ack('approve', key, res);
}

function dispute(data) {
  const p = pick(data.projects);
  const key = newKey('mx-ds');
  const res = http.post(`${BASE}/api/escrow/disputes`,
    JSON.stringify({ milestoneId: pick(p.milestoneIds), reason: 'k6' }),
    Object.assign(jsonHeaders(actor(p.clientId, 'CLIENT'), key), { tags: { name: 'dispute' } }));
  ack('dispute', key, res);
}

function payout(data) {
  const f = pick(data.freelancers);
  const key = newKey('mx-po');
  // Freelancer wallets fill up only as milestones are approved, so early payouts get 422.
  const res = http.post(`${BASE}/api/v1/payouts`, JSON.stringify({ amountPaise: randInt(100, 2000) }),
    Object.assign(jsonHeaders(actor(f.userId, 'FREELANCER'), key), { tags: { name: 'payout' } }));
  if (res.status === 422) insufficientFunds.add(1);
  ack('payout', key, res);
}

/** K2 reads these lines from k6's console output: every 2xx is a promise the ledger must keep. */
function ack(kind, key, res) {
  if (ACK_LOG && res.status >= 200 && res.status < 300) {
    console.log(`ACK ${kind} ${key}`);
  }
}

export function handleSummary(data) {
  return summarize('mixed', RATE, DURATION_SEC, data);
}
