// Shared helpers for the k6 scenarios.
import http from 'k6/http';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
export const RESULTS_DIR = __ENV.RESULTS_DIR || 'load/results';
export const LABEL = __ENV.LABEL || 'default';

export const ADMIN = { 'X-Actor-Id': '1', 'X-Actor-Role': 'ADMIN' };

// 409 (state conflict) and 422 (insufficient funds) are correct business answers under
// load, not failures. Everything else outside 2xx counts toward http_req_failed.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 422));

export function jsonHeaders(extra = {}, key = null) {
  const h = Object.assign({ 'Content-Type': 'application/json' }, extra);
  if (key) h['Idempotency-Key'] = key;
  return { headers: h };
}

let seq = 0;
export function newKey(prefix) {
  seq += 1;
  return `${prefix}-${__VU}-${seq}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function randInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

/** Trend stats every scenario reports (p99 is not in k6's default set). */
export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'];

/**
 * Thresholds on the "main" scenario's submetrics. Besides the real pass/fail limits, a
 * submetric only appears in the end-of-test summary if some threshold references it, so the
 * count>=0 entries are there to keep setup() traffic out of the reported numbers.
 */
export function baseThresholds(extra = {}) {
  return Object.assign({
    'http_req_failed{scenario:main}': ['rate<0.01'],
    'http_req_duration{scenario:main}': ['p(95)>=0'],
    'http_reqs{scenario:main}': ['count>=0'],
  }, extra);
}

function metric(data, name, stat) {
  const m = data.metrics[name];
  return m && m.values && m.values[stat] !== undefined ? m.values[stat] : null;
}

/**
 * Write a compact result JSON (achieved rps over the scenario window only, not setup) plus a
 * one-line stdout summary. run-bench.sh also passes --summary-export for the raw numbers.
 */
export function summarize(scenario, targetRps, durationSec, data) {
  const count = metric(data, 'http_reqs{scenario:main}', 'count') || 0;
  const result = {
    scenario,
    label: LABEL,
    targetRps,
    durationSec,
    requests: count,
    achievedRps: Math.round((count / durationSec) * 10) / 10,
    p50: metric(data, 'http_req_duration{scenario:main}', 'med'),
    p95: metric(data, 'http_req_duration{scenario:main}', 'p(95)'),
    p99: metric(data, 'http_req_duration{scenario:main}', 'p(99)'),
    errorRate: metric(data, 'http_req_failed{scenario:main}', 'rate'),
    droppedIterations: metric(data, 'dropped_iterations', 'count') || 0,
    insufficientFunds: metric(data, 'insufficient_funds', 'count') || 0,
    replayed: metric(data, 'replayed', 'count') || 0,
    transferP95: metric(data, 'transfer_latency', 'p(95)'),
    thresholdsPassed: Object.values(data.metrics).every(
      (m) => !m.thresholds || Object.values(m.thresholds).every((t) => t.ok)),
  };
  const line = `${scenario} [${LABEL}] target=${targetRps}rps achieved=${result.achievedRps}rps `
    + `p50=${fmt(result.p50)} p95=${fmt(result.p95)} p99=${fmt(result.p99)} `
    + `err=${((result.errorRate || 0) * 100).toFixed(2)}% dropped=${result.droppedIterations} `
    + `thresholds=${result.thresholdsPassed ? 'PASS' : 'FAIL'}\n`;
  return {
    [`${RESULTS_DIR}/${scenario}-${LABEL}.result.json`]: JSON.stringify(result, null, 2),
    stdout: line,
  };
}

function fmt(ms) {
  return ms === null ? 'n/a' : `${ms.toFixed(1)}ms`;
}
