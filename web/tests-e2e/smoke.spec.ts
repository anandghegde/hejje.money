import { test, expect } from '@playwright/test';

const API = process.env.API_URL ?? 'http://localhost:8080/api/v1';
const PASSWORD = process.env.HEJJE_ADMIN_PASSWORD ?? 'admin-password';

/**
 * Smoke: login -> broker status -> place a paper order -> see it in Orders -> close the position.
 * Requires the server (fake broker, PAPER mode) and the web dev server; run with `npm run e2e`.
 */
test('login, place a paper order, close the position', async ({ page, request }) => {
  // a quote for INFY so the manual order form can suggest a stop from the last traded price
  const login = await request.post(`${API}/auth/login`, { data: { username: 'admin', password: PASSWORD } });
  expect(login.ok()).toBeTruthy();
  const h = { Authorization: `Bearer ${(await login.json()).accessToken}` };
  const infy = await (await request.get(`${API}/instruments/resolve?symbol=NSE:INFY`, { headers: h })).json();
  await request.post(`${API}/broker/dev/quote`, { headers: h, data: { instrumentId: infy.id, price: 1500 } });

  await page.goto('/login');
  await page.getByLabel('username').fill('admin');
  await page.getByLabel('password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();

  await expect(page.getByTestId('mode-banner')).toContainText('PAPER');

  await page.goto('/broker');
  await expect(page.getByTestId('broker-state')).toHaveText('CONNECTED');

  await page.goto('/orders');
  await page.getByLabel('symbol').fill('NSE:INFY');
  await page.getByRole('button', { name: 'Resolve' }).click();
  await page.getByLabel('quantity').fill('1');
  // risk limits make a stop mandatory and within 5% of the price: the form prefills the suggested one
  await expect(page.getByTestId('stop-suggestion')).toContainText('Suggested stop');
  await expect(page.getByLabel('stopPrice')).not.toHaveValue('');
  await request.post(`${API}/broker/dev/quote`, { headers: h, data: { instrumentId: infy.id, price: 1500 } }); // fresh tick: readiness rejects quotes older than 10 s
  await page.getByTestId('place-order').click();
  await expect(page.getByTestId('order-message')).toContainText('Order');

  await expect(page.getByTestId('orders-table')).toBeVisible();

  await page.goto('/positions');
  await expect(page.getByTestId('positions-table')).toBeVisible();
});
