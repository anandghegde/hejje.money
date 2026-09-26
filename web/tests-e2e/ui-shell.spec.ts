import { test, expect, Page } from '@playwright/test';

const PASSWORD = process.env.HEJJE_ADMIN_PASSWORD ?? 'admin-password';
const VIEWPORTS = [{ width: 360, height: 780 }, { width: 768, height: 1024 }, { width: 1440, height: 900 }];

async function login(page: Page) {
  await page.goto('/login');
  await page.getByLabel('username').fill('admin');
  await page.getByLabel('password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByTestId('mode-banner')).toBeVisible();
}

/**
 * Phase 10 app shell (M10.2): at phone, tablet and desktop widths the mode banner and kill-switch state are visible and
 * the page never scrolls sideways; `/design` renders every component. Screenshots land in test-results/.
 * The file sorts after the trading specs on purpose: its many page loads use up the admin's request burst, which
 * paper-flow and smoke (one login each, then many calls) need.
 */
test('the shell and /design at 360, 768 and 1440 px', async ({ page }, info) => {
  await login(page);
  for (const vp of VIEWPORTS) {
    await page.setViewportSize(vp);
    for (const path of ['/today', '/design']) {
      await page.goto(path);
      await expect(page.getByTestId('mode-banner')).toBeVisible();
      await expect(page.getByTestId('kill-state')).toBeVisible();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${path} scrolls sideways at ${vp.width} px`).toBeLessThanOrEqual(0);
    }
    await page.screenshot({ path: info.outputPath(`shell-${vp.width}.png`) }); // the viewport: banner, nav or tab bar
    await expect(page.getByTestId('design-light')).toBeVisible();
    await expect(page.getByTestId('design-dark')).toBeVisible();
    await page.screenshot({ path: info.outputPath(`design-${vp.width}.png`), fullPage: true });
  }
});

/** M10.3: the trading pages at phone and desktop size in both themes, with no sideways page scroll. */
test('trading pages at 390 and 1440 px in both themes', async ({ page }, info) => {
  await login(page);
  for (const colorScheme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme });
    for (const vp of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
      await page.setViewportSize(vp);
      for (const path of ['/today', '/positions', '/orders', '/trades', '/approvals', '/risk', '/broker']) {
        await page.goto(path);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        await page.waitForLoadState('networkidle');
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `${path} scrolls sideways at ${vp.width} px`).toBeLessThanOrEqual(0);
        await page.screenshot({ path: info.outputPath(`${path.slice(1)}-${vp.width}-${colorScheme}.png`), fullPage: true });
      }
    }
  }
});

test('phone: the tab bar and the menu reach every page', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 780 });
  await login(page);
  const main = page.getByRole('navigation', { name: 'Main' });
  await expect(main).toBeHidden();
  await page.getByRole('navigation', { name: 'Quick links' }).getByRole('link', { name: 'Positions', exact: true }).click();
  await expect(page).toHaveURL(/\/positions$/);
  await page.getByRole('button', { name: 'Menu' }).click();
  await expect(main).toBeVisible();
  await main.getByRole('link', { name: 'Settings', exact: true }).click();
  await expect(page).toHaveURL(/\/settings$/);
  await expect(main).toBeHidden();
});

test('the theme switch applies at once and follows the OS by default', async ({ page }, info) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await login(page);
  await page.goto('/settings');
  const bg = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  const dark = await bg();
  await page.screenshot({ path: info.outputPath('settings-dark.png'), fullPage: true });
  await page.getByTestId('theme-select').selectOption('light');
  await expect.poll(bg).not.toBe(dark);
  await page.screenshot({ path: info.outputPath('settings-light.png'), fullPage: true });
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.getByTestId('theme-select').selectOption('system');
  await expect.poll(bg).toBe(dark);
});

/** M10.6 exit check: every page at 360, 768 and 1440 px in both themes; banner and kill state visible, no sideways page scroll. */
test('every page at 360, 768 and 1440 px in both themes', async ({ page, request }, info) => {
  test.setTimeout(300_000);
  const API = process.env.API_URL ?? 'http://localhost:8080/api/v1';
  const token = (await (await request.post(`${API}/auth/login`, { data: { username: 'admin', password: PASSWORD } })).json()).accessToken;
  const strategies = await (await request.get(`${API}/strategies`, { headers: { Authorization: `Bearer ${token}` } })).json();
  const pages = ['/today', '/approvals', '/orders', '/positions', '/trades', '/risk', '/swing', '/risk/policies', '/broker', '/options', '/screener',
    '/stocks/NSE:INFY', '/pulse', '/strategies', `/strategies/${strategies[0].id}`, '/lab', '/reviews', '/analytics', '/agent', '/system',
    '/settings', '/design'];
  await login(page);
  for (const colorScheme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme });
    for (const vp of VIEWPORTS) {
      await page.setViewportSize(vp);
      for (const path of pages) {
        await page.goto(path);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        await page.waitForLoadState('networkidle');
        await expect(page.getByTestId('mode-banner')).toBeInViewport();
        await expect(page.getByTestId('kill-state')).toBeVisible();
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `${path} scrolls sideways at ${vp.width} px (${colorScheme})`).toBeLessThanOrEqual(0);
        if (['/stocks/NSE:INFY', '/analytics'].includes(path) || path.startsWith('/strategies/')) {
          const name = path.startsWith('/strategies/') ? 'strategy-detail' : path.split('/').filter(Boolean).join('-').replace(':', '-');
          await page.screenshot({ path: info.outputPath(`${name}-${vp.width}-${colorScheme}.png`), fullPage: true });
        }
      }
    }
  }
});

test('login works on a phone', async ({ page }, info) => {
  await page.setViewportSize({ width: 360, height: 780 });
  await page.goto('/login');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.screenshot({ path: info.outputPath('login-360.png') });
  await login(page);
});
