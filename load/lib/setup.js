// setup(): build the data every scenario runs against, through the public/admin APIs only.
//   500 client wallets, each funded with a simulated deposit
//   50 freelancers
//   200 projects x 3 milestones, funded and submitted (ready to approve or dispute)
// Sizes can be shrunk for smoke runs with CLIENTS / FREELANCERS / PROJECTS env vars.
import http from 'k6/http';
import { fail } from 'k6';
import { BASE, ADMIN, jsonHeaders, newKey } from './common.js';

const CLIENTS = parseInt(__ENV.CLIENTS || '500', 10);
const FREELANCERS = parseInt(__ENV.FREELANCERS || '50', 10);
const PROJECTS = parseInt(__ENV.PROJECTS || '200', 10);
const DEPOSIT_PAISE = 10_000_000; // ₹1,00,000 per client wallet
const BATCH = 50;

export function setupData() {
  const run = `${Date.now()}`;
  const clients = createUsers('CLIENT', CLIENTS, run);
  const freelancers = createUsers('FREELANCER', FREELANCERS, run);

  // Fund every client wallet.
  batch(clients.map((c) => ['POST', `${BASE}/api/v1/admin/deposits/simulate`,
    JSON.stringify({ clientAccountId: c.walletId, amountPaise: DEPOSIT_PAISE }),
    jsonHeaders(ADMIN, newKey(`setup-dep-${run}`))]), 201, 'simulate deposit');

  // Projects: create, fund, then submit all 3 milestones.
  const due = new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString();
  const specs = [];
  for (let i = 0; i < PROJECTS; i++) {
    specs.push({ client: clients[i % clients.length], freelancer: freelancers[i % freelancers.length] });
  }
  const created = batch(specs.map((s, i) => ['POST', `${BASE}/api/escrow/projects`, JSON.stringify({
    clientId: s.client.userId,
    freelancerId: s.freelancer.userId,
    title: `load-${run}-${i}`,
    reviewWindow: 'P5D',
    milestones: [10000, 20000, 30000].map((amt, m) => ({ title: `m${m + 1}`, amountPaise: amt + (i % 100) * 100, dueAt: due })),
  }), jsonHeaders({ 'X-Actor-Id': `${s.client.userId}`, 'X-Actor-Role': 'CLIENT' })]), 201, 'create project');
  const projectIds = created.map((r) => r.json('id'));

  batch(projectIds.map((id, i) => ['POST', `${BASE}/api/escrow/projects/${id}/fund`, null,
    jsonHeaders({ 'X-Actor-Id': `${specs[i].client.userId}`, 'X-Actor-Role': 'CLIENT' }, `setup-fund-${run}-${id}`)]),
    200, 'fund project');

  const details = batch(projectIds.map((id) => ['GET', `${BASE}/api/escrow/projects/${id}`, null, {}]), 200, 'get project');
  const projects = details.map((r, i) => ({
    projectId: projectIds[i],
    clientId: specs[i].client.userId,
    freelancerId: specs[i].freelancer.userId,
    milestoneIds: r.json('milestones').map((m) => m.id),
  }));

  const submits = [];
  projects.forEach((p) => p.milestoneIds.forEach((mid) => submits.push(['POST',
    `${BASE}/api/escrow/milestones/${mid}/submit`, null,
    jsonHeaders({ 'X-Actor-Id': `${p.freelancerId}`, 'X-Actor-Role': 'FREELANCER' }, `setup-sub-${run}-${mid}`)])));
  batch(submits, 200, 'submit milestone');

  return { clients, freelancers, projects };
}

function createUsers(role, n, run) {
  const users = batch(Array.from({ length: n }, (_, i) => ['POST', `${BASE}/api/escrow/users`,
    JSON.stringify({ name: `${role.toLowerCase()}-${run}-${i}`, role }), jsonHeaders(ADMIN)]), 201, `create ${role}`);
  const ids = users.map((r) => r.json('id'));
  const prefix = role === 'CLIENT' ? 'CLIENT_WALLET' : 'FREELANCER_WALLET';
  const wallets = batch(ids.map((id) => ['GET', `${BASE}/api/v1/accounts/by-code/${prefix}:${id}`, null, {}]),
    200, `lookup ${prefix}`);
  return ids.map((id, i) => ({ userId: id, walletId: wallets[i].json('id') }));
}

/** http.batch in chunks; abort setup on any unexpected status. */
function batch(requests, expectedStatus, what) {
  const out = [];
  for (let i = 0; i < requests.length; i += BATCH) {
    const responses = http.batch(requests.slice(i, i + BATCH));
    responses.forEach((r) => {
      if (r.status !== expectedStatus) {
        fail(`setup: ${what} returned ${r.status}: ${String(r.body).slice(0, 300)}`);
      }
    });
    out.push(...responses);
  }
  return out;
}
