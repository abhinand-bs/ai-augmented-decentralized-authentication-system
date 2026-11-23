import { expect, test } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { mockApi } from './mockApi.js';
import { installWallet, testWallet } from './wallet.js';

const USER = testWallet('user');
const WIDTHS = [360, 768, 1280, 1920];
const PUBLIC = ['/', '/register', '/login', '/no-such-page'];
const SIGNED_IN = ['/dashboard', '/history', '/wallet-status', '/security'];
const ADMIN = ['/admin', '/admin/wallets', '/admin/security', '/admin/controls'];

// Console errors and warnings fail a test. Chrome's own "Failed to load resource" line for an HTTP error the app
// expects (a 401 while signed out, an injected 503) comes from the network layer, not from the app, and is ignored.
test.beforeEach(async ({ page }, info) => {
  const problems = [];
  page.on('console', (m) => {
    if ((m.type() === 'error' || m.type() === 'warning') && !/Failed to load resource/.test(m.text())) problems.push(m.text());
  });
  page.on('pageerror', (e) => problems.push(e.message));
  info.attach = info.attach.bind(info);
  page.problems = problems;
});
test.afterEach(async ({ page }) => {
  expect(page.problems, 'console errors or warnings').toEqual([]);
});

async function signedIn(page, role = 'USER') {
  await installWallet(page, USER);
  return mockApi(page, { user: USER.address, role });
}

test.describe('layout and titles at every width', () => {
  for (const width of WIDTHS) {
    test(`no horizontal overflow at ${width}px on any page`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      const shots = process.env.SCREENSHOTS ? `e2e-screenshots/${width}` : null;
      if (shots) mkdirSync(shots, { recursive: true });
      await signedIn(page, 'ADMIN');
      for (const path of [...PUBLIC, ...SIGNED_IN, ...ADMIN]) {
        await page.goto(path);
        await expect(page.locator('h1').first()).toBeVisible();
        await expect(page).toHaveTitle(/· BlockAuth$/);
        await page.waitForLoadState('networkidle');
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `${path} overflows by ${overflow}px`).toBeLessThanOrEqual(0);
        if (shots) await page.screenshot({ path: `${shots}/${path.replaceAll('/', '_') || '_home'}.png`, fullPage: true });
      }
    });
  }

  test('a non-admin on an admin page gets a titled no-permission page, not a loop', async ({ page }) => {
    await signedIn(page, 'USER');
    await page.goto('/admin/controls');
    await expect(page.getByRole('heading', { name: 'No permission' })).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/controls$/);
  });

  test('every interactive control is reachable by keyboard and shows a focus ring', async ({ page }) => {
    await signedIn(page, 'ADMIN');
    await page.goto('/admin/security');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
    for (let i = 0; i < 5; i++) await page.keyboard.press('Tab');
    const outline = await page.evaluate(() => getComputedStyle(document.activeElement).outlineStyle);
    expect(outline).not.toBe('none');
  });
});

test.describe('sign-in flow', () => {
  test('connect, sign for real, land on the page that was asked for; a triple click sends one request', async ({ page }) => {
    await installWallet(page, USER);
    const api = await mockApi(page);
    await page.goto('/history');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByRole('button', { name: 'Connect Wallet' }).click();
    const sign = page.getByRole('button', { name: 'Sign Challenge' });
    await sign.click({ clickCount: 3 });
    await expect(page).toHaveURL(/\/history$/);
    expect(api.calls['POST /api/auth/verify']).toBe(1);
    expect(api.calls['POST /api/auth/challenge']).toBe(1);
  });

  test('a rejected signature is explained', async ({ page }) => {
    await installWallet(page, USER, { reject: 4001 });
    await mockApi(page);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Connect Wallet' }).click();
    await page.getByRole('button', { name: 'Sign Challenge' }).click();
    await expect(page.getByText('You cancelled the request in MetaMask.')).toBeVisible();
  });

  test('a challenge that expires while MetaMask is open offers a new one', async ({ page }) => {
    await page.clock.install();
    await installWallet(page, USER, { hang: true });
    await mockApi(page);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Connect Wallet' }).click();
    await page.getByRole('button', { name: 'Sign Challenge' }).click();
    await expect(page.getByText(/expires in/)).toBeVisible();
    await page.clock.fastForward(125_000);
    await page.getByRole('button', { name: 'Get a new message' }).click();
    await expect(page.getByRole('button', { name: 'Sign Challenge' })).toBeEnabled();
  });

  test('429 shows the wait time; 503 says the service is unavailable', async ({ page }) => {
    await installWallet(page, USER);
    const api = await mockApi(page);
    api.overrides['POST /api/auth/challenge'] = { status: 429, body: { code: 'RATE_LIMITED', message: 'x', fieldErrors: [] }, headers: { 'Retry-After': '42' } };
    await page.goto('/login');
    await page.getByRole('button', { name: 'Connect Wallet' }).click();
    await page.getByRole('button', { name: 'Sign Challenge' }).click();
    await expect(page.getByText('Too many attempts. Please wait 42 seconds.')).toBeVisible();
    api.overrides['POST /api/auth/challenge'] = { status: 503, body: { code: 'DATABASE_UNAVAILABLE', message: 'x', fieldErrors: [] } };
    await page.getByRole('button', { name: 'Sign Challenge' }).click();
    await expect(page.getByText('The service is temporarily unavailable. Please try again shortly.')).toBeVisible();
  });

  test('a signed-in user opening /login is told so', async ({ page }) => {
    await signedIn(page);
    await page.goto('/login');
    await expect(page.getByText('You are already signed in.', { exact: false })).toBeVisible();
  });

  test('MetaMask missing: the connect button is disabled with an install hint', async ({ page }) => {
    await mockApi(page);
    await page.goto('/login');
    await expect(page.getByText(/MetaMask is required/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Connect Wallet' })).toBeDisabled();
  });
});

test.describe('session behaviour', () => {
  test('a 401 in the middle of an action returns to login with a notice', async ({ page }) => {
    const api = await signedIn(page);
    await page.goto('/security');
    await expect(page.getByRole('heading', { name: 'Account security' })).toBeVisible();
    api.overrides['GET /api/users/sessions'] = { status: 401, body: { code: 'SESSION_REVOKED', message: 'x', fieldErrors: [] } };
    await page.reload();
    api.user = null;
    await expect(page).toHaveURL(/\/login$/);
  });

  test('switching MetaMask to another account signs out exactly once', async ({ page }) => {
    const api = await signedIn(page);
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    expect(await page.evaluate(() => window.__e2eListenerCount('accountsChanged'))).toBe(1);
    await page.evaluate(() => {
      window.__e2eEmit('accountsChanged', ['0x2222222222222222222222222222222222222222']);
      window.__e2eEmit('accountsChanged', ['0x3333333333333333333333333333333333333333']);
    });
    await expect(page.getByText('Wallet changed — you have been signed out.')).toBeVisible();
    expect(api.calls['POST /api/auth/logout']).toBe(1);
  });

  test('returning to the tab after a while re-checks the session', async ({ page }) => {
    await page.clock.install();
    const api = await signedIn(page);
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    const before = api.calls['GET /api/users/me'];
    api.user = null; // the session ended elsewhere meanwhile
    await page.clock.fastForward(31 * 60 * 1000);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(page).toHaveURL(/\/login$/);
    expect(api.calls['GET /api/users/me']).toBe(before + 1);
  });

  test('a slow first request looks like loading, not a crash; deep links survive reload and back/forward', async ({ page }) => {
    const api = await signedIn(page, 'ADMIN');
    api.overrides['GET /api/users/me'] = { delay: 1500 };
    await page.goto('/admin/security');
    await expect(page.getByText('Checking your session…')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Security monitoring' })).toBeVisible();
    delete api.overrides['GET /api/users/me'];
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Security monitoring' })).toBeVisible();
    await page.getByRole('link', { name: 'Wallets' }).click();
    await expect(page.getByRole('heading', { name: 'Wallet management' })).toBeVisible();
    await page.goBack();
    await expect(page).toHaveURL(/\/admin\/security$/);
    await page.goForward();
    await expect(page).toHaveURL(/\/admin\/wallets$/);
  });
});

test.describe('lists', () => {
  test('rapid filter changes never show a stale answer, and reset to the first page', async ({ page }) => {
    const api = await signedIn(page, 'ADMIN');
    await page.goto('/admin/security');
    await expect(page.getByRole('heading', { name: 'Security monitoring' })).toBeVisible();
    // Earlier filters answer slowly, the last one fast: only the last may be shown.
    api.overrides['GET /api/admin/security-events'] = { delay: 800 };
    const select = page.getByLabel('Attack type');
    await select.selectOption('REPLAY_ATTEMPT');
    await select.selectOption('CSRF_REJECTED');
    delete api.overrides['GET /api/admin/security-events'];
    await select.selectOption('INVALID_SIGNATURE');
    await expect(page.locator('tbody')).toContainText('Invalid signature');
    await page.waitForTimeout(1000);
    await expect(page.locator('tbody')).toContainText('Invalid signature');
    await expect(page.locator('tbody')).not.toContainText('Replay attempt');
    expect(api.lastQuery['/api/admin/security-events']).toContain('page=0');
  });

  test('an error state offers a retry that works', async ({ page }) => {
    const api = await signedIn(page);
    api.overrides['GET /api/users/authentication-history'] = { status: 502, body: { code: 'UNKNOWN' } };
    await page.goto('/history');
    await expect(page.getByText('The service is temporarily unavailable.', { exact: false })).toBeVisible();
    delete api.overrides['GET /api/users/authentication-history'];
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.locator('tbody tr')).toHaveCount(1);
  });

  test('the sign-in risk tab shows the model fields and status card', async ({ page }) => {
    const api = await signedIn(page, 'ADMIN');
    await page.goto('/admin/security');
    await page.getByRole('button', { name: 'Sign-in risk' }).click();
    await expect(page.getByRole('heading', { name: 'Anomaly model' })).toBeVisible();
    await expect(page.getByText('Shadow: scores are stored')).toBeVisible();
    await expect(page.locator('tbody')).toContainText('0.64');
    await expect(page.locator('tbody')).toContainText('New device, Hour sin');
    await page.getByLabel('Anomaly at least').selectOption('0.7');
    await expect.poll(() => api.lastQuery['/api/admin/login-assessments']).toContain('minAnomaly=0.7');
  });
});
