import { test, expect, APIRequestContext } from '@playwright/test';

const API = process.env.API_URL ?? 'http://localhost:8080/api/v1';
const PASSWORD = process.env.HEJJE_ADMIN_PASSWORD ?? 'admin-password';

/** ₹ with two decimals, grouped the Indian way (lib/sizing formatPaise). */
const rupees = (paise: number) => `₹${(paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

async function login(request: APIRequestContext) {
  const res = await request.post(`${API}/auth/login`, { data: { username: 'admin', password: PASSWORD } });
  expect(res.ok()).toBeTruthy();
  return { Authorization: `Bearer ${(await res.json()).accessToken}` };
}

/** The open swing book; empty on a refused call (a 429 while the burst refills) so a poll simply tries again. */
async function swingBook(request: APIRequestContext, h: Record<string, string>): Promise<{ symbol: string; gtt: string }[]> {
  const res = await request.get(`${API}/swing/positions`, { headers: h });
  return res.ok() ? res.json() : [];
}

/**
 * Plan M11.6: a PAPER delivery (swing) entry shows on the Swing page with its GTT, the same numbers as the API, the
 * overnight risk on the Swing and Risk pages, the phone cards, and the exit through the confirmation dialog.
 */
test('the swing book: a delivery position with its GTT, overnight risk, phone cards and the exit', async ({ page, request }) => {
  const h = await login(request);
  const tcs = await (await request.get(`${API}/instruments/resolve?symbol=NSE:TCS`, { headers: h })).json();
  await request.post(`${API}/market/subscriptions`, { headers: h, data: { instrumentIds: [tcs.id] } });
  await request.post(`${API}/broker/dev/quote`, { headers: h, data: { instrumentId: tcs.id, price: 100 } });
  const placed = await request.post(`${API}/orders/intents`, {
    headers: { ...h, 'Idempotency-Key': `e2e-swing-${Date.now()}` },
    data: { instrumentId: tcs.id, side: 'BUY', quantity: 5, orderType: 'MARKET', product: 'CNC', stopPrice: '93.00', targetPrice: '120.00' },
  });
  expect(placed.status(), await placed.text()).toBeLessThan(300);

  // the fill opens the swing position and its GTT is placed within seconds (polled gently: the admin's request burst is shared)
  await expect.poll(async () => (await swingBook(request, h)).find((p) => p.symbol === 'NSE:TCS')?.gtt,
    { timeout: 15000, intervals: [500, 1000] }).toBe('ACTIVE');
  const book = await (await request.get(`${API}/swing/positions`, { headers: h })).json();
  const row = book.find((p: { symbol: string }) => p.symbol === 'NSE:TCS');
  const risk = await (await request.get(`${API}/swing/risk`, { headers: h })).json();

  await page.goto('/login');
  await page.getByLabel('username').fill('admin');
  await page.getByLabel('password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByTestId('mode-banner')).toContainText('PAPER');

  await page.getByRole('link', { name: 'Swing' }).first().click();
  await expect(page.getByRole('heading', { name: 'Swing' })).toBeVisible();
  const table = page.getByTestId('swing-table');
  const tr = table.getByRole('row').filter({ hasText: 'NSE:TCS' });
  await expect(tr).toContainText(row.entryDate);
  await expect(tr).toContainText(Number(row.stop).toFixed(2));
  await expect(tr).toContainText(Number(row.goal).toFixed(2));
  await expect(page.getByTestId('swing-gtt-NSE:TCS')).toHaveText('✓ GTT active');
  // the page shows the API's numbers
  await expect(page.getByTestId('swing-overnight-risk')).toContainText(rupees(risk.overnightRisk.paise));
  await expect(page.getByTestId('swing-open')).toContainText(`${risk.openPositions}/${risk.maxOpenPositions}`);

  // the Risk page carries the same gap-adjusted total
  const dashboard = await (await request.get(`${API}/risk`, { headers: h })).json();
  await page.goto('/risk');
  await expect(page.getByTestId('risk-overnight')).toContainText(rupees(dashboard.overnightRisk.paise));

  // phone: the positions become cards
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/swing');
  await expect(page.getByTestId('swing-cards')).toContainText('NSE:TCS');
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/swing');

  // the exit confirms through the dialog, then the position is gone
  await request.post(`${API}/broker/dev/quote`, { headers: h, data: { instrumentId: tcs.id, price: 101 } });
  await tr.getByRole('button', { name: 'Exit' }).click();
  await page.getByRole('button', { name: 'Exit at market' }).click();
  await expect.poll(async () => (await swingBook(request, h)).some((p) => p.symbol === 'NSE:TCS'),
    { timeout: 15000, intervals: [500, 1000] }).toBe(false);
});
