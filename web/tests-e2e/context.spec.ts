import { test, expect } from '@playwright/test';

const API = process.env.API_URL ?? 'http://localhost:8080/api/v1';
const PASSWORD = process.env.HEJJE_ADMIN_PASSWORD ?? 'admin-password';

/**
 * Daily context smoke (plan M8.7): seed D1 candles for the fixture equities, compute ratings, bases and daily analogs,
 * then open the Screener and a stock page. Requires the server with HEJJE_RATINGS_ENABLED=true HEJJE_ANALOGS_ENABLED=true
 * HEJJE_RATINGS_UNIVERSE=nifty50 HEJJE_ANALOGS_UNIVERSE=nifty50 (of which the fake broker's master resolves eight symbols).
 */
test('screener lists the rated universe and the stock page shows ratings, chart and analogs', async ({ page, request }) => {
  const login = await request.post(`${API}/auth/login`, { data: { username: 'admin', password: PASSWORD } });
  expect(login.ok()).toBeTruthy();
  const admin = { Authorization: `Bearer ${(await login.json()).accessToken}` };
  // the seeding and compute calls go through a key of their own: rate-limit buckets are per principal, and the specs run in
  // parallel as the same admin user
  const client = await request.post(`${API}/auth/clients`, { headers: admin, data: { name: `e2e-context-${Date.now()}`, scopes: ['admin', 'market:read'] } });
  expect(client.status()).toBe(201);
  const h = { Authorization: `Bearer ${(await client.json()).key}` };

  // 150 weekday sessions ending last Friday: symbol k drifts up (0.05 + 0.04 k) % a session with a deterministic wobble
  const days: string[] = [];
  const end = new Date();
  end.setUTCDate(end.getUTCDate() - 1);
  for (const d = new Date(end); days.length < 150; d.setUTCDate(d.getUTCDate() - 1)) {
    if (d.getUTCDay() !== 0 && d.getUTCDay() !== 6) days.unshift(d.toISOString().slice(0, 10));
  }
  const symbols = ['NSE:RELIANCE', 'NSE:INFY', 'NSE:TCS', 'NSE:HDFCBANK', 'NSE:SBIN'];
  for (let k = 0; k < symbols.length; k++) {
    const instrument = await (await request.get(`${API}/instruments/resolve?symbol=${symbols[k]}`, { headers: h })).json();
    let close = 500 + 100 * k;
    const candles = days.map((day, i) => {
      close *= 1 + (0.05 + 0.04 * k) / 100 + Math.sin(i * (k + 2)) * 0.008;
      const c = Math.round(close * 100) / 100;
      return { openTime: `${day}T00:00:00+05:30`, open: c, high: Math.round(c * 101) / 100, low: Math.round(c * 99) / 100, close: c, volume: 100000 + 1000 * (i % 7) };
    });
    const seeded = await request.post(`${API}/market/dev/candles`, { headers: h, data: { instrumentId: instrument.id, timeframe: 'D1', store: true, publish: false,
      quote: false, candles } });
    expect(seeded.ok()).toBeTruthy();
  }
  const last = days[days.length - 1];
  const from = days[days.length - 10];
  expect((await request.post(`${API}/ratings/compute`, { headers: h, data: { from, to: last } })).ok()).toBeTruthy();
  expect((await request.post(`${API}/ratings/bases/compute`, { headers: h, data: { from: days[0], to: last } })).ok()).toBeTruthy();
  expect((await request.post(`${API}/analogs/compute`, { headers: h, data: { dates: [last], lookbacks: [15] } })).ok()).toBeTruthy();

  await page.goto('/login');
  await page.getByLabel('username').fill('admin');
  await page.getByLabel('password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByTestId('mode-banner')).toBeVisible();

  await page.goto('/screener');
  await expect(page.getByTestId('market-condition')).toBeVisible();
  await page.getByRole('button', { name: 'Custom screen' }).click();
  await page.getByRole('button', { name: 'remove filter 0' }).click();
  await page.getByTestId('run-screen').click();
  await expect(page.getByTestId('screen-summary')).toContainText('5 of 5 stocks match');
  await expect(page.getByTestId('screener-table')).toContainText('NSE:SBIN');
  await expect(page.getByTestId('saved-screens')).toContainText('Leaders');

  // one click from the table to the stock page
  await page.getByRole('link', { name: 'NSE:INFY' }).first().click();
  await expect(page).toHaveURL(/\/stocks\/NSE(:|%3A)INFY/);
  await expect(page.getByTestId('ratings-block')).toContainText('RS rating');
  await expect(page.getByTestId('daily-chart')).toBeVisible();
  await expect(page.getByTestId('analog-panel')).toContainText('not validated');
  await expect(page.getByTestId('analog-outcomes')).toContainText('5 sessions');
  // every rate carries its count: "N of M (P %)" or "no matches"
  await expect(page.getByTestId('analog-outcomes')).toContainText(/\d+ of \d+ \(\d+ %\)|no matches/);
  await expect(page.getByTestId('analog-read').locator('li')).toHaveCount(5);

  await page.getByTestId('watch-toggle').click();
  await expect(page.getByTestId('watch-toggle')).toContainText('Watching');
  await page.getByTestId('watch-toggle').click();
  await expect(page.getByTestId('watch-toggle')).toContainText('Watch');
});
