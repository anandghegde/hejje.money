import { defineConfig } from '@playwright/test';

/**
 * Smoke test config. Requires the Hejje server running with the fake broker in PAPER mode and the Vite dev server.
 * In CI both are started before `npm run e2e` (see .github/workflows/ci.yml).
 */
export default defineConfig({
  testDir: './tests-e2e',
  timeout: 30000,
  use: { baseURL: process.env.WEB_URL ?? 'http://localhost:5173', headless: true },
  webServer: process.env.CI_NO_WEBSERVER ? undefined : {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60000,
  },
});
