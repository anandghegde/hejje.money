import { test, expect } from '@playwright/test';

const API = process.env.API_URL ?? 'http://localhost:8080/api/v1';
const PASSWORD = process.env.HEJJE_ADMIN_PASSWORD ?? 'admin-password';

/**
 * Hejje AI chat (plan M4.3). With the fixture LLM enabled on the stack (`HEJJE_LLM_ENABLED=true` plus a `type: fixture`
 * provider, see docs/agents.md) the "What is working today?" flow streams an answer with its tool-call trace; with the
 * LLM off (the default) the page shows the disabled state.
 */
test('Hejje AI answers through tools with a visible trace, or explains that it is off', async ({ page, request }) => {
  const login = await request.post(`${API}/auth/login`, { data: { username: 'admin', password: PASSWORD } });
  expect(login.ok()).toBeTruthy();
  const h = { Authorization: `Bearer ${(await login.json()).accessToken}` };
  const status = await (await request.get(`${API}/agents/ai/status`, { headers: h })).json();
  if (status.enabled) {
    const registered = await request.post(`${API}/agents/llm/dev/fixture`, { headers: h,
      data: { contains: 'What is working today?', response: 'No round trips have closed yet today, so nothing is working or failing yet [get_pnl_breakdown].' } });
    expect(registered.ok(), await registered.text()).toBeTruthy();
  }

  await page.goto('/login');
  await page.getByLabel('username').fill('admin');
  await page.getByLabel('password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByTestId('mode-banner')).toContainText('PAPER');
  await page.getByRole('link', { name: 'Hejje AI' }).click();

  if (!status.enabled) {
    await expect(page.getByTestId('ai-disabled')).toContainText('Hejje AI is off');
    return;
  }
  await page.getByRole('button', { name: 'What is working today?' }).click();
  await expect(page.getByTestId('ai-answer')).toContainText('No round trips have closed yet today');
  await expect(page.getByTestId('ai-answer')).toContainText('traced to tool results');
  const trace = page.getByTestId('ai-trace');
  await expect(trace).toContainText('get_pnl_breakdown');
  await expect(trace).toContainText('get_strategy_rankings');
  await expect(trace).toContainText('OK');
});
