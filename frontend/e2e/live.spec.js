// Live end-to-end run against the REAL stack (backend instances on the Neon test branch, Redis, Hardhat,
// Mailpit). Nothing here is mocked except MetaMask itself: the wallet stub signs with real keys. Different client
// networks are simulated with X-Forwarded-For, which the local proxy passes to a backend that trusts it.
// Run: E2E_LIVE=1 npx playwright test (see e2e/README.md). Phase "enforce" needs the backend in ENFORCE mode.
import { expect, test } from '@playwright/test';
import { createHmac } from 'node:crypto';
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { Interface, Wallet } from 'ethers';
import { installWallet, testWallet } from './wallet.js';

const SHOTS = process.env.LIVE_SHOTS || '../BlockAuth-Feature-Screenshots';
// Every browser: 1280x720 CSS pixels at 1.5x, so a viewport screenshot is exactly 1920x1080 and the app's
// 1120-pixel content column fills the frame.
const VIEW = { viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1.5 };
const RPC = 'http://127.0.0.1:8545';
const LOG = process.env.LIVE_LOG || 'live-results/live-results.jsonl'; // not test-results: Playwright empties that per run
const MAILPIT = 'http://127.0.0.1:8025';
const BACKEND = 'http://127.0.0.1:8080';
const ADMIN = testWallet('admin');
const PHASE = process.env.LIVE_PHASE || 'main';
// Run 3 found that re-registering the admin each run recorded a failure from its address (correctly counted
// by REPEATED_FAILURES); the admin is now registered only when needed, and its address can be changed.
const ADMIN_IP = process.env.LIVE_ADMIN_IP || '192.0.2.11';
// Failures count per client address for an hour (REPEATED_FAILURES), so runs within the same hour must not share
// addresses (run 4 found this). Each run takes its own last octet inside the same documentation ranges.
const R = Math.floor(Math.random() * 100);
const IP = {
  home: `203.0.113.${20 + R}`, travel: `198.51.100.${20 + R}`, tablet: `198.51.100.${130 + R}`,
  stranger: `198.51.100.${230 + (R % 25)}`, other: `192.0.2.${150 + R}`,
  enforceHome: `203.0.113.${130 + R}`, enforceTablet: `198.51.100.${(R % 19) + 1}`,
};
mkdirSync(SHOTS, { recursive: true });
mkdirSync('live-results', { recursive: true });

const record = (step, data) => appendFileSync(LOG, `${JSON.stringify({ phase: PHASE, step, ...data })}\n`);
const problems = [];

function totp(secretBase32, at = Date.now()) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of secretBase32.replace(/=+$/, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30000)));
  const h = createHmac('sha1', key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

/** A browser "device" on a "network": its own cookies, and its own client address via the trusted proxy. */
async function device(browser, wallet, ip, label) {
  const context = await browser.newContext({ ...VIEW, extraHTTPHeaders: { 'X-Forwarded-For': ip } });
  const page = await context.newPage();
  page.on('console', (m) => {
    if (['error', 'warning'].includes(m.type())) problems.push({ device: label, type: m.type(), text: m.text().slice(0, 200) });
  });
  page.on('pageerror', (e) => problems.push({ device: label, type: 'pageerror', text: e.message }));
  page.on('response', (r) => {
    if (r.status() >= 500) problems.push({ device: label, type: 'http', text: `${r.status()} ${r.url()}` });
  });
  await installWallet(page, wallet);
  return page;
}

/**
 * A 1920x1080 screenshot of the visible window. `focus` (if given) is scrolled to the top edge, `pad` CSS pixels
 * below it, or to the bottom edge with `end`. `fromColumn` scrolls the last table sideways so that this column
 * (0-based) starts at its left edge: the risk table is wider than the 1120-pixel page and scrolls sideways.
 */
async function shot(page, file, focus, { pad = 16, end = false, fromColumn } = {}) {
  if (focus) {
    await focus.evaluate((e, atEnd) => e.scrollIntoView({ block: atEnd ? 'end' : 'start' }), end);
    await page.evaluate((dy) => window.scrollBy(0, dy), end ? pad : -pad);
  }
  if (fromColumn !== undefined) {
    await page.locator('.table-wrap').last().evaluate((wrap, i) => {
      const th = wrap.querySelectorAll('th')[i];
      wrap.scrollLeft += th.getBoundingClientRect().left - wrap.getBoundingClientRect().left;
    }, fromColumn);
  }
  await page.waitForTimeout(250);
  await page.screenshot({ path: `${SHOTS}/${file}` });
}

async function rpc(method, params) {
  const r = await fetch(RPC, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
  return (await r.json()).result;
}

async function connect(page) {
  const button = page.getByRole('button', { name: 'Connect Wallet' });
  if (await button.isVisible().catch(() => false)) await button.click();
  await expect(page.getByText('Connected')).toBeVisible();
}

async function signIn(page) {
  await page.goto('/login');
  await connect(page);
  const verifies = [];
  page.on('request', (r) => r.url().endsWith('/api/auth/verify') && verifies.push(r));
  const verified = page.waitForResponse((r) => r.url().endsWith('/api/auth/verify')); // registered before the click
  await page.getByRole('button', { name: 'Sign Challenge' }).click({ clickCount: 3 });
  await verified;
  await page.waitForTimeout(300);
  return verifies.length;
}

async function register(page) {
  await page.goto('/register');
  await connect(page);
  await page.getByRole('button', { name: 'Sign & Register' }).click();
  await expect(page.getByText(/Registration complete|already registered/)).toBeVisible({ timeout: 45_000 });
}

/** Signs a step-up confirmation the way the page does it, through the page's own MetaMask stub. */
async function adminApi(page, path) {
  const r = await page.request.get(path);
  expect(r.status(), path).toBe(200);
  return r.json();
}

async function lastAssessment(adminPage, wallet) {
  const page = await adminApi(adminPage, `/api/admin/login-assessments?walletAddress=${wallet.address}&size=1`);
  return page.items[0];
}

async function mail(toAddress, subjectPart) {
  for (let i = 0; i < 40; i++) {
    const list = await (await fetch(`${MAILPIT}/api/v1/messages?limit=50`)).json();
    const found = list.messages.find((m) => m.To.some((t) => t.Address === toAddress) && m.Subject.includes(subjectPart));
    if (found) return (await fetch(`${MAILPIT}/api/v1/message/${found.ID}`)).json();
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no mail "${subjectPart}" to ${toAddress}`);
}

test.describe.configure({ mode: 'serial' });

test('main: sign-ins, risk decisions, second step, alerts, e-mail, admin controls', async ({ browser }) => {
  test.skip(PHASE !== 'main');
  const user = Wallet.createRandom();
  const other = Wallet.createRandom();
  const email = `live-${user.address.slice(2, 10).toLowerCase()}@example.test`;
  writeFileSync('live-results/live-wallets.json', JSON.stringify({ user: user.address, other: other.address, email, ip: IP }));

  // ---- Administrator (needed to read the stored assessments) ----
  const admin = await device(browser, ADMIN, ADMIN_IP, 'admin');
  expect(await signIn(admin)).toBe(1);
  if (await admin.getByText('This wallet is not registered.').isVisible()) {
    await register(admin);
    await signIn(admin);
  }
  await expect(admin.getByRole('heading', { name: 'Admin dashboard' })).toBeVisible();

  const check = async (step, wallet, expected) => {
    const a = await lastAssessment(admin, wallet);
    record(step, { wallet: wallet.address, score: a.score, ruleScore: a.ruleScore, anomaly: a.anomalyScore, final: a.finalScore,
      rules: a.rules, decision: a.decision, mode: a.mode, model: a.modelVersion, top: a.topFeatures });
    expect(a.rules, `${step} rules`).toBe(expected.rules);
    expect(a.decision, `${step} decision`).toBe(expected.decision);
    expect(a.ruleScore, `${step} rule score`).toBe(expected.score);
    return a;
  };

  await test.step('S1 first sign-in: nothing to compare with -> 100, ALLOW', async () => {
    const laptop = await device(browser, user, IP.home, 'laptop');
    await register(laptop);
    await laptop.getByText('Registration details').click();
    await expect(laptop.getByText(/0x[0-9a-f]{64}/)).toBeVisible(); // transaction hash of the on-chain registration
    const sent = await signIn(laptop);
    expect(sent, 'a triple click sends one verify request').toBe(1);
    await expect(laptop.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S1 first sign-in', user, { rules: '', decision: 'ALLOW', score: 100 });
    globalThis.laptop = laptop;
  });
  const laptop = globalThis.laptop;

  await test.step('S2 same device, same network -> 100, ALLOW', async () => {
    await laptop.getByRole('button', { name: 'Logout' }).first().click();
    await expect(laptop).toHaveURL(/\/$/);
    await signIn(laptop);
    await expect(laptop.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S2 known device and network', user, { rules: '', decision: 'ALLOW', score: 100 });
  });

  await test.step('S3 new device, known network -> 70 NEW_DEVICE, ALLOW; the laptop is alerted', async () => {
    const phone = await device(browser, user, IP.home, 'phone');
    await signIn(phone);
    await expect(phone.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S3 new device', user, { rules: 'NEW_DEVICE', decision: 'ALLOW', score: 70 });
    await laptop.goto('/dashboard');
    await expect(laptop.getByText('Your wallet signed in on a new device.')).toBeVisible();
    await laptop.goto('/security');
    await expect(laptop.getByRole('region', { name: 'New sign-ins to check' })).toBeVisible();
    await expect(laptop.getByRole('region', { name: 'Signed-in sessions' }).locator('tbody tr')).toHaveCount(2);
    await laptop.getByRole('button', { name: 'This was me' }).click();
    await expect(laptop.getByRole('region', { name: 'New sign-ins to check' })).toHaveCount(0);
    await phone.context().close();
  });

  await test.step('S4 known device, new network -> 80 NEW_NETWORK, ALLOW', async () => {
    await laptop.setExtraHTTPHeaders({ 'X-Forwarded-For': IP.travel });
    await laptop.getByRole('button', { name: 'Logout' }).first().click();
    await signIn(laptop);
    await expect(laptop.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S4 new network', user, { rules: 'NEW_NETWORK', decision: 'ALLOW', score: 80 });
    await laptop.setExtraHTTPHeaders({ 'X-Forwarded-For': IP.home });
  });

  await test.step('S5 new device and new network -> 50, ALLOW (50-89 band, no second step)', async () => {
    const tablet = await device(browser, user, IP.tablet, 'tablet');
    await signIn(tablet);
    await expect(tablet.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S5 new device and network', user, { rules: 'NEW_DEVICE,NEW_NETWORK', decision: 'ALLOW', score: 50 });
    await tablet.context().close();
  });

  await test.step('S6 three failures from one address, then a new device there -> 25, REFUSE', async () => {
    for (let i = 0; i < 3; i++) {
      const stranger = await device(browser, Wallet.createRandom(), IP.stranger, `stranger${i}`);
      await signIn(stranger);
      await expect(stranger.getByText('This wallet is not registered.')).toBeVisible();
      await stranger.context().close();
    }
    const intruder = await device(browser, user, IP.stranger, 'intruder');
    await signIn(intruder);
    await expect(intruder.getByText('This sign-in was refused because it does not look like your usual sign-ins.')).toBeVisible();
    await shot(intruder, '04-risk-decision-login-refused.png', intruder.getByRole('alert').first(), { end: true, pad: 8 });
    await check('S6 repeated failures + new device + new network', user,
      { rules: 'NEW_DEVICE,NEW_NETWORK,REPEATED_FAILURES', decision: 'REFUSE', score: 25 });
    await intruder.context().close();
  });

  let secret;
  let backupCodes;
  await test.step('second step: set up with a real TOTP code and a wallet confirmation', async () => {
    await laptop.goto('/security');
    const section = laptop.getByRole('region', { name: 'Second step' });
    await section.getByRole('button', { name: 'Set up the second step' }).click();
    secret = (await section.locator('dd.mono').first().textContent()).trim();
    await section.getByLabel('Code shown in your app').fill(totp(secret));
    await section.getByRole('button', { name: 'Turn on' }).click();
    await expect(section.getByLabel('Backup codes')).toBeVisible();
    backupCodes = (await section.getByLabel('Backup codes').locator('li').allTextContents()).map((c) => c.trim());
    expect(backupCodes).toHaveLength(10);
    record('second step enabled', { backupCodes: backupCodes.length });
  });

  await test.step('S7 new device with a second step -> 70, SECOND_STEP; a TOTP code completes it', async () => {
    const phone2 = await device(browser, user, IP.home, 'phone2');
    await signIn(phone2);
    await expect(phone2.getByRole('heading', { name: 'Enter your verification code' })).toBeVisible();
    await check('S7 new device, second step on', user, { rules: 'NEW_DEVICE', decision: 'SECOND_STEP', score: 70 });
    await shot(phone2, '05-risk-decision-extra-verification.png');
    await phone2.waitForTimeout(31_000); // the setup code's 30-second step was used once already
    await phone2.getByLabel('Verification code').fill(totp(secret));
    await phone2.getByRole('button', { name: 'Continue' }).click();
    await expect(phone2.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await phone2.context().close();
  });

  await test.step('S8 another new device -> SECOND_STEP; a backup code completes it, and a wrong code is refused', async () => {
    const phone3 = await device(browser, user, IP.home, 'phone3');
    await signIn(phone3);
    await phone3.getByLabel('Verification code').fill('000000');
    await phone3.getByRole('button', { name: 'Continue' }).click();
    await expect(phone3.getByText('That code is not right, or it was already used.')).toBeVisible();
    await phone3.getByLabel('Verification code').fill(backupCodes[0]);
    await phone3.getByRole('button', { name: 'Continue' }).click();
    await expect(phone3.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S8 new device, backup code', user, { rules: 'NEW_DEVICE', decision: 'SECOND_STEP', score: 70 });
    await phone3.context().close();
  });

  await test.step('e-mail alerts: address set with a signature, confirmed with the mailed code', async () => {
    await laptop.goto('/security');
    const section = laptop.getByRole('region', { name: 'Alert e-mail' });
    await section.getByLabel('E-mail address').fill(email);
    await section.getByRole('button', { name: 'Send a confirmation code' }).click();
    const verification = await mail(email, 'confirm your alert e-mail address');
    const code = verification.Text.match(/\b(\d{6})\b/)[1];
    await section.getByLabel('Code from the e-mail').fill(code);
    await section.getByRole('button', { name: 'Confirm address' }).click();
    await expect(section.getByText('Confirmed')).toBeVisible();
    await shot(laptop, '07-alert-email-setup-confirmed.png', laptop.getByText(/^With the second step on, a sign-in/), { pad: 8 });
    record('alert e-mail confirmed', { to: email });
  });

  await test.step('S9 a new device triggers the alert e-mail (after the second step)', async () => {
    const phone4 = await device(browser, user, IP.travel, 'phone4');
    await signIn(phone4);
    await phone4.getByLabel('Verification code').fill(backupCodes[1]);
    await phone4.getByRole('button', { name: 'Continue' }).click();
    await expect(phone4.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    const alert = await mail(email, 'signed in on a new device');
    expect(alert.Text).toContain('If this was not you, open BlockAuth and choose This was not me.');
    expect(alert.Text).toContain('198.51.100.x');
    expect(alert.Text).not.toMatch(/https?:/);
    record('alert e-mail received', { subject: alert.Subject, network: '198.51.100.x' });
    const inbox = await (await browser.newContext(VIEW)).newPage();
    await inbox.goto(`${MAILPIT}/view/${alert.ID}`);
    await inbox.waitForLoadState('networkidle');
    await shot(inbox, '06-new-device-alert-email.png');
    await inbox.close();
    await phone4.context().close();
  });

  await test.step('Redis: the laptop session from instance A is served by instance B (port 5174 -> backend 18081)', async () => {
    await laptop.goto('http://localhost:5174/security');
    await expect(laptop.getByRole('heading', { name: 'Account security' })).toBeVisible();
    await expect(laptop.getByRole('region', { name: 'Signed-in sessions' }).getByText('This device')).toBeVisible();
    record('redis shared session', { url: 'http://localhost:5174/security', backend: '18081' });
  });

  // ---- A second wallet for the flag, revoke and reactivation scenarios ----
  const otherLaptop = await device(browser, other, IP.other, 'other-laptop');
  await register(otherLaptop);
  await signIn(otherLaptop);
  await check('S10 other wallet first sign-in', other, { rules: '', decision: 'ALLOW', score: 100 });

  await test.step('flag the other wallet (admin, signed confirmation)', async () => {
    await admin.goto('/admin/controls');
    const flags = admin.getByRole('region', { name: 'Suspicious wallets' });
    await flags.getByLabel('Wallet').fill(other.address);
    await flags.getByLabel('Reason').fill('Reported in a phishing campaign (live test)');
    await flags.getByRole('button', { name: 'Flag wallet' }).click();
    await expect(flags.locator('tbody')).toContainText(other.address.slice(0, 6));
  });

  await test.step('S11 flagged wallet on a new device -> 35, REFUSE; on its known device -> 65, ALLOW', async () => {
    const otherPhone = await device(browser, other, IP.other, 'other-phone');
    await signIn(otherPhone);
    await expect(otherPhone.getByText('This sign-in was refused because it does not look like your usual sign-ins.')).toBeVisible();
    await check('S11 flagged + new device', other, { rules: 'NEW_DEVICE,FLAGGED_WALLET', decision: 'REFUSE', score: 35 });
    await otherPhone.context().close();
    await otherLaptop.getByRole('button', { name: 'Logout' }).first().click();
    await signIn(otherLaptop);
    await expect(otherLaptop.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S12 flagged on known device', other, { rules: 'FLAGGED_WALLET', decision: 'ALLOW', score: 65 });
  });

  await test.step('S13 the user signs in on the browser of the flagged wallet; afterwards a new device is LINKED_TO_FLAGGED', async () => {
    // Same browser (device cookie) as the flagged wallet's laptop, used by the user's wallet: a shared device.
    const shared = await device(browser, user, IP.home, 'shared');
    await shared.context().addCookies((await otherLaptop.context().cookies()).filter((c) => c.name === 'BLOCKAUTH_DEVICE'));
    await signIn(shared);
    await shared.getByLabel('Verification code').fill(backupCodes[2]);
    await shared.getByRole('button', { name: 'Continue' }).click();
    await expect(shared.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await check('S13a sign-in on the device of the flagged wallet', user, { rules: 'NEW_DEVICE', decision: 'SECOND_STEP', score: 70 });
    await shared.context().close();
    const fresh = await device(browser, user, IP.home, 'fresh');
    await signIn(fresh);
    await expect(fresh.getByRole('heading', { name: 'Enter your verification code' })).toBeVisible();
    await check('S13b new device, wallet now linked to a flagged one', user,
      { rules: 'NEW_DEVICE,LINKED_TO_FLAGGED', decision: 'SECOND_STEP', score: 35 });
    await fresh.context().close();
  });

  await test.step('remove the flag again', async () => {
    await admin.goto('/admin/controls');
    const flags = admin.getByRole('region', { name: 'Suspicious wallets' });
    await flags.getByRole('button', { name: 'Remove flag' }).first().click();
    await expect(flags.getByText(other.address.slice(0, 6))).toHaveCount(0);
  });

  await test.step('admin risk view, model card, dashboard and monitoring', async () => {
    await admin.goto('/admin/security');
    await admin.getByRole('button', { name: 'Sign-in risk' }).click();
    await expect(admin.getByRole('heading', { name: 'Anomaly model' })).toBeVisible();
    await admin.getByLabel('Wallet').fill(user.address);
    await expect(admin.locator('tbody tr')).toHaveCount(11, { timeout: 15_000 });
    await shot(admin, '01-risk-scoring-assessment.png', admin.getByLabel('Decision'), { pad: 48, fromColumn: 1 });
    // Monitoring view: every refused sign-in, all wallets, with the model card above.
    await admin.getByLabel('Wallet').fill('');
    await admin.getByLabel('Decision').selectOption('REFUSE');
    await expect(admin.locator('tbody tr').first()).toContainText('Refuse', { timeout: 15_000 });
    await shot(admin, '02-risk-monitoring-refused-sign-ins.png', admin.getByRole('heading', { name: 'Anomaly model' }),
      { fromColumn: 0 }); // back to the left edge: the time matters here
    await admin.goto('/admin');
    await expect(admin.getByRole('heading', { name: 'Admin dashboard' })).toBeVisible();
    await expect(admin.getByRole('heading', { name: 'Security events' })).toBeVisible({ timeout: 20_000 }); // loaded, not the spinner
    await admin.goto('/admin/security');
    await admin.getByLabel('Attack type').selectOption('');
    await expect(admin.locator('tbody')).toContainText('Low trust login');
    await admin.getByRole('button', { name: 'Authentication events' }).click();
    await expect(admin.locator('tbody')).toContainText('Alert email sent');
  });

  await test.step('revoke the other wallet on the chain; its open session is refused', async () => {
    await admin.goto('/admin/wallets');
    await admin.getByLabel('Check any wallet on the blockchain').fill(other.address);
    await admin.locator('form.lookup').getByRole('button', { name: 'Check on-chain' }).click();
    await admin.getByRole('region', { name: 'On-chain status' }).getByRole('button', { name: 'Revoke' }).click();
    await admin.getByRole('button', { name: 'Revoke wallet' }).click();
    await expect(admin.getByText(/is now revoked/)).toBeVisible({ timeout: 45_000 });
    await admin.getByText('Transaction details').click();
    // The chain write is an EIP-1559 (type 2) transaction and succeeded.
    const hash = (await admin.locator('.tx-hash .mono').first().textContent()).trim();
    const tx = await rpc('eth_getTransactionByHash', [hash]);
    const receipt = await rpc('eth_getTransactionReceipt', [hash]);
    record('revoke transaction', { hash, type: tx.type, maxFeePerGas: tx.maxFeePerGas, status: receipt.status });
    expect(tx.type).toBe('0x2');
    expect(receipt.status).toBe('0x1');
    const registry = new Interface(['function isActive(address) view returns (bool)']);
    const active = registry.decodeFunctionResult('isActive',
      await rpc('eth_call', [{ to: tx.to, data: registry.encodeFunctionData('isActive', [other.address]) }, 'latest']))[0];
    record('revoked wallet on chain', { wallet: other.address, isActive: active });
    expect(active).toBe(false);
    // The list reloads after the revocation (old rows shown as stale meanwhile); wait for the new answer.
    await expect(admin.locator('tbody tr', { hasText: `${other.address.slice(0, 6)}` }).first()).toContainText('Revoked', { timeout: 20_000 });
    await shot(admin, '08-blockchain-transaction-eip1559.png', admin.getByRole('heading', { name: 'Wallet management' }));
    const otherAgain = await device(browser, other, IP.other, 'other-again');
    await signIn(otherAgain);
    await expect(otherAgain.getByText('This wallet has been revoked by an administrator.')).toBeVisible();
    await otherAgain.context().close();
  });

  await test.step('delayed reactivation is requested, waits, and can be cancelled', async () => {
    await admin.getByLabel('Check any wallet on the blockchain').fill(other.address);
    await admin.locator('form.lookup').getByRole('button', { name: 'Check on-chain' }).click();
    await admin.getByRole('region', { name: 'On-chain status' }).getByRole('button', { name: 'Activate' }).click();
    await admin.getByRole('button', { name: 'Request reactivation' }).click();
    await expect(admin.getByText(/Reactivation of .* is requested/)).toBeVisible({ timeout: 45_000 });
    await admin.goto('/admin/controls');
    const waiting = admin.getByRole('region', { name: 'Waiting actions' });
    await expect(waiting.locator('tbody')).toContainText('Activate wallet');
    await waiting.getByRole('button', { name: 'Cancel' }).first().click();
    await expect(waiting.getByText('Activate wallet')).toHaveCount(0, { timeout: 30_000 });
  });

  await test.step('API key: created with a signature, reads with its scope, cannot write, revoked', async () => {
    await admin.goto('/admin/controls');
    const keys = admin.getByRole('region', { name: 'API keys' });
    // The form's day limit comes from the server (GET /api/admin/api-keys/limits).
    const limits = await adminApi(admin, '/api/admin/api-keys/limits');
    await expect(keys.getByText(`1 to ${limits.maxDays}.`)).toBeVisible();
    record('api key limits', limits);
    await keys.getByLabel('Name').fill('live-test');
    await keys.getByLabel(/audit:read/).check();
    await keys.getByLabel('Valid for (days)').fill('1');
    await keys.getByRole('button', { name: 'Create key' }).click();
    const tokenText = (await keys.locator('.alert-success p.mono').textContent()).trim();
    const read = await fetch(`${BACKEND}/api/admin/statistics`, { headers: { 'X-API-Key': tokenText } });
    const write = await fetch(`${BACKEND}/api/admin/users`, { headers: { 'X-API-Key': tokenText } });
    record('api key', { readStatistics: read.status, readUsersWithoutScope: write.status });
    expect(read.status).toBe(200);
    expect(write.status).toBe(403);
    // Revoke every live-test key still active (including one left by an interrupted run).
    const active = keys.locator('tbody tr', { hasText: 'live-test' }).filter({ has: admin.getByRole('button', { name: 'Revoke' }) });
    while (await active.count() > 0) {
      const before = await active.count();
      await active.first().getByRole('button', { name: 'Revoke' }).click();
      await admin.getByRole('button', { name: 'Revoke key' }).click();
      await expect(active).toHaveCount(before - 1, { timeout: 30_000 });
    }
    await expect.poll(async () => (await fetch(`${BACKEND}/api/admin/statistics`, { headers: { 'X-API-Key': tokenText } })).status)
      .toBe(401);
  });

  if (process.env.LIVE_SKIP_STOP !== '1') await test.step('emergency stop: on with blocked sign-ins, user refused, lift requested (waits 10 min)', async () => {
    await admin.goto('/admin/controls');
    const stop = admin.getByRole('region', { name: 'Emergency stop' });
    await stop.getByLabel('Reason').fill('Live test of the emergency stop');
    await stop.getByRole('button', { name: 'Switch on the emergency stop' }).click();
    await expect(stop.getByText('The emergency stop is on')).toBeVisible({ timeout: 45_000 });
    const blocked = await device(browser, user, IP.home, 'blocked');
    await signIn(blocked);
    await expect(blocked.getByText('This is paused by an administrator.')).toBeVisible();
    await blocked.context().close();
    await stop.getByRole('button', { name: 'Request to lift it' }).click();
    await expect(admin.getByText(/Requested\. It takes effect at/)).toBeVisible({ timeout: 45_000 });
  });

  record('console and server problems', { problems });
});

test('pages: every remaining route as a signed-in user, the admin guard and unknown paths', async ({ browser }) => {
  test.skip(PHASE !== 'pages');
  const user = Wallet.createRandom();
  const page = await device(browser, user, IP.home, 'pages-user');
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in with your wallet, not a password.' })).toBeVisible();
  await register(page);
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await page.goto('/history');
  await expect(page.getByRole('heading', { name: 'Authentication history' })).toBeVisible();
  await expect(page.getByRole('table', { name: /Your authentication events/ }).locator('tbody tr')).toHaveCount(2); // registration + login
  await page.goto('/wallet-status');
  await expect(page.getByRole('heading', { name: 'Wallet status' })).toBeVisible();
  await expect(page.locator('dd').filter({ hasText: 'Active' }).first()).toBeVisible({ timeout: 20_000 }); // read from the contract
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'No permission' })).toBeVisible();
  await page.goto('/admin/controls');
  await expect(page.getByRole('heading', { name: 'No permission' })).toBeVisible();
  const forbidden = await page.request.get('/api/admin/statistics');
  expect(forbidden.status()).toBe(403);
  // Session re-check (new): a session ended from another device is noticed when this tab gets focus again
  // (at most every 30 s), without any click here.
  await page.goto('/dashboard'); // last check happens now; the page then stays loaded, untouched
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  const second = await device(browser, user, IP.home, 'pages-second');
  await signIn(second);
  await expect(second.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await second.goto('/security');
  await second.getByRole('button', { name: 'Sign out everywhere else' }).click();
  await expect(second.getByRole('region', { name: 'Signed-in sessions' }).locator('tbody tr')).toHaveCount(1);
  await page.waitForTimeout(31_000); // past the 30-second re-check interval
  await expect(page).toHaveURL(/\/dashboard/); // still there: nothing but the focus event may send it away
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page).toHaveURL(/\/login/, { timeout: 15_000 });
  record('session re-check on focus', { endedElsewhere: true, redirectedTo: '/login' });
  await signIn(page); // and the ended browser can sign in again at once (A-34)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await second.context().close();
  await page.goto('/no-such-page');
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  await page.goto('/dashboard');
  await page.getByRole('button', { name: 'Logout' }).first().click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto('/security');
  await expect(page).toHaveURL(/\/login/); // protected page after logout
  record('pages', { wallet: user.address, routes: ['/', '/register', '/login', '/dashboard', '/history', '/wallet-status', '/admin (403 page)', '/admin/controls (403 page)', '/no-such-page', '/security after logout'], adminApiAsUser: forbidden.status() });
  record('pages console and server problems', { problems });
});

test('lift: the delayed lift of the emergency stop was carried out; leftover live-test flags removed', async ({ browser }) => {
  test.skip(PHASE !== 'lift');
  const admin = await device(browser, ADMIN, ADMIN_IP, 'admin');
  await signIn(admin);
  await admin.goto('/admin/controls');
  const stop = admin.getByRole('region', { name: 'Emergency stop' });
  await expect(stop.getByRole('button', { name: 'Switch on the emergency stop' })).toBeVisible({ timeout: 20_000 });
  const waiting = admin.getByRole('region', { name: 'Waiting actions' });
  await waiting.getByLabel('Status').selectOption('EXECUTED');
  await expect(waiting.locator('tbody')).toContainText('Lift emergency stop', { timeout: 20_000 });
  // Flags left by an interrupted run (removing a flag is refused while the stop is on).
  const flags = admin.getByRole('region', { name: 'Suspicious wallets' });
  const live = flags.locator('tbody tr', { hasText: '(live test)' });
  while (await live.count() > 0) {
    const before = await live.count();
    await live.first().getByRole('button', { name: 'Remove flag' }).click();
    await expect(live).toHaveCount(before - 1, { timeout: 30_000 });
  }
  await stop.scrollIntoViewIfNeeded();
  record('emergency stop lifted', { stopOff: true, liftExecutedListed: true });
  record('lift console and server problems', { problems });
});

test('enforce: the anomaly model lowers the rule score (backend restarted in ENFORCE mode)', async ({ browser }) => {
  test.skip(PHASE !== 'enforce');
  const user = Wallet.createRandom();
  const admin = await device(browser, ADMIN, ADMIN_IP, 'admin');
  await signIn(admin);
  const laptop = await device(browser, user, IP.enforceHome, 'enforce-laptop');
  await register(laptop);
  await signIn(laptop);
  const tablet = await device(browser, user, IP.enforceTablet, 'enforce-tablet');
  await signIn(tablet);
  const a = await lastAssessment(admin, user);
  record('E1 enforce: new device + new network', { wallet: user.address, ruleScore: a.ruleScore, anomaly: a.anomalyScore,
    final: a.finalScore, rules: a.rules, decision: a.decision, mode: a.mode, top: a.topFeatures });
  expect(a.mode).toBe('ENFORCE');
  expect(a.ruleScore).toBe(50);
  // The penalty the backend must apply (TrustPolicy: max 30 points, threshold from ANOMALY_THRESHOLD), and its decision
  // for a wallet without the second step (allowed from 50).
  const threshold = Number(process.env.LIVE_ANOMALY_THRESHOLD || 0.5);
  const penalty = Math.round(30 * Math.min(1, Math.max(0, (a.anomalyScore - threshold) / (1 - threshold))));
  expect(a.finalScore).toBe(a.ruleScore - penalty);
  expect(a.decision).toBe(a.finalScore >= 50 ? 'ALLOW' : 'REFUSE');
  await admin.goto('/admin/security');
  await admin.getByRole('button', { name: 'Sign-in risk' }).click();
  await admin.getByLabel('Wallet').fill(user.address);
  await expect(admin.locator('tbody tr')).toHaveCount(2, { timeout: 15_000 });
  await shot(admin, '03-learned-risk-model-combined-score.png', admin.getByText(/^Every sign-in whose signature checked out/), { pad: 0 });
  record('enforce console and server problems', { problems });
});
