import { defineConfig, devices } from '@playwright/test';

// Two layers. "ui": the production build served by `vite preview`, every /api call answered by a mock in the
// test (fast, no backend). "fullstack": the dev server against a real backend and Hardhat node; opt-in with
// E2E_FULLSTACK=1 because it needs those running (see e2e/README.md).
const fullstack = process.env.E2E_FULLSTACK === '1';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: !fullstack,
  workers: fullstack ? 1 : undefined,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: fullstack
    ? [{ name: 'fullstack', testMatch: /fullstack\.spec\.js/, use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:5173' } }]
    : [{ name: 'ui', testIgnore: /fullstack\.spec\.js/, use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:4173' } }],
  webServer: fullstack
    ? { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true, timeout: 120_000 }
    : { command: 'npm run build && npx vite preview --port 4173 --strictPort', url: 'http://localhost:4173', reuseExistingServer: !process.env.CI, timeout: 120_000 },
});
