import { test, expect } from '@playwright/test';

const API = process.env.API_URL ?? 'http://localhost:8080/api/v1';
const PASSWORD = process.env.HEJJE_ADMIN_PASSWORD ?? 'admin-password';

/**
 * Agent-prepared order with human confirmation (plan M4.4): an `execution`-preset API key submits an order intent through
 * the agent tool API, the approvals badge appears, the user approves in the inbox and the order goes through in PAPER.
 * Uses NSE:TCS so it never collides with the INFY specs running in parallel.
 */
test('an agent proposal is approved from the inbox and sent in PAPER', async ({ page, request }) => {
  const login = await request.post(`${API}/auth/login`, { data: { username: 'admin', password: PASSWORD } });
  expect(login.ok()).toBeTruthy();
  const h = { Authorization: `Bearer ${(await login.json()).accessToken}` };
  const tcs = await (await request.get(`${API}/instruments/resolve?symbol=NSE:TCS`, { headers: h })).json();
  // runs at any time of day and repeatedly: lift the time cutoff and the per-day limits on the paper ledger (as the paper-flow spec does)
  const limits = await (await request.get(`${API}/risk/limits`, { headers: h })).json();
  expect((await request.put(`${API}/risk/limits`, { headers: h, data: {
    maxLossPerDayPaise: 100000000, maxRealizedLossPaise: 100000000, maxTotalLossPaise: 100000000,
    maxCapitalDeployedPaise: limits.maxCapitalDeployed.paise, maxMarginUtilizationPct: limits.maxMarginUtilizationPct, maxOpenPositions: 50,
    maxGrossExposurePaise: limits.maxGrossExposure.paise, maxTradesPerDay: 500, maxRiskPerTradePaise: limits.maxRiskPerTrade.paise,
    maxQuantity: limits.maxQuantity, maxNotionalPaise: limits.maxNotional.paise, minRewardRisk: limits.minRewardRisk, mandatoryStop: limits.mandatoryStop,
    maxStopDistancePct: limits.maxStopDistancePct, noNewTradesAfter: '23:59', noAveragingDown: false, noReentryMinutes: 0,
    maxConsecutiveLosses: 500 } })).ok()).toBeTruthy();
  await request.delete(`${API}/risk/kill-switch`, { headers: { ...h, 'Idempotency-Key': `e2e-approvals-rearm-${Date.now()}` } });

  const client = await request.post(`${API}/auth/clients`, { headers: h, data: { name: `e2e-agent-${Date.now()}`, preset: 'execution' } });
  expect(client.status()).toBe(201);
  const agent = { Authorization: `Bearer ${(await client.json()).key}` };
  // subscribe TCS so its dev quotes reach the market pipeline (market-data readiness needs a fresh tick once any spec starts streaming)
  expect((await request.post(`${API}/market/subscriptions`, { headers: h, data: { instrumentIds: [tcs.id] } })).ok()).toBeTruthy();
  await request.post(`${API}/broker/dev/quote`, { headers: h, data: { instrumentId: tcs.id, price: 3500 } });
  const submitted = await request.post(`${API}/agents/tools/submit_order_intent`, { headers: agent,
    data: { instrument: 'NSE:TCS', side: 'BUY', riskRupees: 500, entry: 3500, stop: 3490, target: 3520, rationale: 'e2e: breakout above the morning high' } });
  expect(submitted.ok(), await submitted.text()).toBeTruthy();
  const approvalId = (await submitted.json()).output.approvalId;
  // the agent key itself cannot approve (no orders:execute)
  expect((await request.post(`${API}/approvals/${approvalId}/approve`, { headers: { ...agent, 'Idempotency-Key': `e2e-self-${Date.now()}` } })).status()).toBe(403);

  await page.goto('/login');
  await page.getByLabel('username').fill('admin');
  await page.getByLabel('password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByTestId('approvals-badge')).toBeVisible();
  await page.getByTestId('approvals-badge').click();
  const card = page.getByTestId('approval-card').filter({ hasText: 'BUY 50 NSE:TCS' });
  await expect(card).toContainText('e2e: breakout above the morning high');
  await expect(card).toContainText('Risk dry run');
  await request.post(`${API}/broker/dev/quote`, { headers: h, data: { instrumentId: tcs.id, price: 3500 } }); // a fresh tick right before approval
  await page.getByTestId(`approve-${approvalId}`).click();
  await expect(page.getByTestId('approval-message')).toContainText('APPROVED');
  await expect(page.getByTestId('decided-approvals')).toContainText('APPROVED');

  const approval = await (await request.get(`${API}/approvals/${approvalId}`, { headers: h })).json();
  expect(approval.status).toBe('APPROVED');
  expect(approval.result.orderId).toBeTruthy();
});
