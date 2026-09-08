import { test, expect } from '@playwright/test';

const PASSWORD = process.env.HEJJE_ADMIN_PASSWORD ?? 'admin-password';

/**
 * Smoke: login -> broker status -> place a paper order -> see it in Orders -> close the position.
 * Requires the server (fake broker, PAPER mode) and the web dev server; run with `npm run e2e`.
 */
test('login, place a paper order, close the position', async ({ page }) => {
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
  await page.getByTestId('place-order').click();
  await expect(page.getByTestId('order-message')).toContainText('Order');

  await expect(page.getByTestId('orders-table')).toBeVisible();

  await page.goto('/positions');
  await expect(page.getByTestId('positions-table')).toBeVisible();
});
