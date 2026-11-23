import { expect, test } from '@playwright/test';
import { existsSync, mkdirSync } from 'node:fs';
import { installWallet, testWallet } from './wallet.js';

// Against the real backend and a local Hardhat node (see e2e/README.md). The admin key's address must be listed
// in the backend's BLOCKAUTH_ADMIN_WALLETS. Every run uses a fresh user wallet.
const ADMIN = testWallet('admin');
// The administrator keeps its browser (device cookie) between runs, like a real administrator would. A fresh
// browser every run would be a new device each time; together with the failed sign-in this test provokes at the
// end, the trust score then (correctly) refuses the administrator from the third run within an hour.
const ADMIN_STATE = 'e2e/.state/admin-device.json';

async function signInAs(browser, wallet, storageState) {
  const context = await browser.newContext(storageState && existsSync(storageState) ? { storageState } : {});
  const page = await context.newPage();
  await installWallet(page, wallet);
  return page;
}

async function connectAndSign(page, button) {
  await page.getByRole('button', { name: 'Connect Wallet' }).click();
  await page.getByRole('button', { name: button }).click();
}

test('register, log in, see dashboard and history, log out; an administrator revokes the user, who is then refused',
  async ({ browser }) => {
    const user = testWallet(`user ${Date.now()}`);
    const page = await signInAs(browser, user);

    await page.goto('/register');
    await connectAndSign(page, 'Sign & Register');
    await expect(page.getByText('Registration complete.')).toBeVisible({ timeout: 45_000 });

    await page.goto('/login');
    await connectAndSign(page, 'Sign Challenge');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await expect(page.getByText(user.address)).toBeVisible();
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await expect(page.locator('tbody')).toContainText('Login');
    await page.getByRole('button', { name: 'Logout' }).first().click();
    await expect(page).toHaveURL(/\/$/);

    const admin = await signInAs(browser, ADMIN, ADMIN_STATE);
    await admin.goto('/register');
    await connectAndSign(admin, 'Sign & Register');
    await expect(admin.getByText(/Registration complete|already registered/)).toBeVisible({ timeout: 45_000 });
    await admin.goto('/login');
    await connectAndSign(admin, 'Sign Challenge');
    await expect(admin.getByRole('heading', { name: 'Admin dashboard' })).toBeVisible();
    mkdirSync('e2e/.state', { recursive: true });
    await admin.context().storageState({ path: ADMIN_STATE });
    await admin.goto('/admin/wallets');
    await admin.getByLabel('Check any wallet on the blockchain').fill(user.address);
    await admin.locator('form.lookup').getByRole('button', { name: 'Check on-chain' }).click();
    await admin.getByRole('region', { name: 'On-chain status' }).getByRole('button', { name: 'Revoke' }).click();
    await admin.getByRole('button', { name: 'Revoke wallet' }).click();
    await expect(admin.getByText(/is now revoked/)).toBeVisible({ timeout: 45_000 });

    await page.goto('/login');
    await connectAndSign(page, 'Sign Challenge');
    await expect(page.getByText('This wallet has been revoked by an administrator.')).toBeVisible();
  });
