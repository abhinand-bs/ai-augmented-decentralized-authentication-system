// A small in-test stand-in for the backend, for the fast UI layer. It keeps a session, issues challenges,
// checks that the signature really belongs to the claimed wallet (ethers.verifyMessage), and lets a test
// override any route (status, body, delay) to inject failures.
import { getAddress, verifyMessage } from 'ethers';

const NOW = () => new Date().toISOString();

function page(items, pageNo = 0, size = 20) {
  return { items, page: pageNo, size, totalElements: items.length, totalPages: Math.max(1, Math.ceil(items.length / size)) };
}

export async function mockApi(page_, { user = null, role = 'USER' } = {}) {
  const state = {
    user: user ? { id: 'u-1', walletAddress: getAddress(user), role, status: 'ACTIVE', createdAt: NOW() } : null,
    calls: {},
    overrides: {},
    challenges: new Map(),
  };

  const json = (route, status, body, headers = {}) => route.fulfill({
    status,
    contentType: 'application/json',
    headers: { Date: new Date().toUTCString(), ...headers },
    body: body === undefined ? '' : JSON.stringify(body),
  });

  await page_.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const key = `${request.method()} ${url.pathname}`;
    state.calls[key] = (state.calls[key] ?? 0) + 1;
    state.lastQuery = { ...(state.lastQuery ?? {}), [url.pathname]: url.search };

    const override = state.overrides[key];
    if (override) {
      if (override.delay) await new Promise((r) => setTimeout(r, override.delay));
      if (override.status) return json(route, override.status, override.body, override.headers);
    }
    const body = request.postDataJSON?.() ?? null;

    switch (key) {
      case 'GET /api/auth/csrf':
        return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Set-Cookie': 'XSRF-TOKEN=e2e; Path=/; SameSite=Strict' }, body: '{}' });
      case 'POST /api/auth/challenge':
      case 'POST /api/auth/register/challenge': {
        const challenge = `localhost wants you to sign in with your Ethereum account:\n${body.walletAddress}\n\nSign in to BlockAuth.\n\nNonce: ${Math.random().toString(16).slice(2)}`;
        const expiresAt = new Date(Date.now() + (override?.ttlMs ?? 120_000)).toISOString();
        state.challenges.set(challenge, body.walletAddress);
        return json(route, 201, { challenge, nonce: 'n', expiresAt });
      }
      case 'POST /api/auth/verify':
      case 'POST /api/auth/register': {
        const signer = verifyMessage(body.challenge, body.signature);
        if (state.challenges.get(body.challenge) !== body.walletAddress || signer !== getAddress(body.walletAddress)) {
          return json(route, 401, { code: 'SIGNATURE_MISMATCH', message: 'Signature does not belong to the specified wallet.', fieldErrors: [] });
        }
        state.challenges.delete(body.challenge);
        if (key.endsWith('/register')) {
          return json(route, 201, { walletAddress: getAddress(body.walletAddress), role: 'USER', status: 'ACTIVE', registeredAt: NOW(), transactionHash: `0x${'ab'.repeat(32)}` });
        }
        state.user = { id: 'u-1', walletAddress: getAddress(body.walletAddress), role, status: 'ACTIVE', createdAt: NOW() };
        return json(route, 200, state.user);
      }
      case 'POST /api/auth/logout':
        state.user = null;
        return route.fulfill({ status: 204, body: '' });
      case 'GET /api/users/me':
        return state.user ? json(route, 200, state.user) : json(route, 401, { code: 'UNAUTHENTICATED', message: 'Authentication required.', fieldErrors: [] });
      default:
        break;
    }
    if (!state.user) return json(route, 401, { code: 'SESSION_REVOKED', message: 'Your session has ended.', fieldErrors: [] });
    if (url.pathname.startsWith('/api/admin') && state.user.role !== 'ADMIN') {
      return json(route, 403, { code: 'ACCESS_DENIED', message: 'Denied', fieldErrors: [] });
    }
    const long = `0x${'7'.repeat(40)}`;
    const rows = {
      '/api/users/authentication-history': page([{ id: 1, occurredAt: NOW(), eventType: url.searchParams.get('eventType') || 'LOGIN', eventStatus: 'SUCCESS', failureReason: null, ipAddress: '2001:0db8:85a3:0000:0000:8a2e:0370:7334' }]),
      '/api/users/wallet-status': { walletAddress: state.user.walletAddress, registered: true, active: true, status: 'ACTIVE', registeredAt: NOW(), source: 'BLOCKCHAIN' },
      '/api/users/sessions': [{ id: 's1', device: 'Chrome on Windows', ipAddress: '127.0.0.1', createdAt: NOW(), lastSeenAt: NOW(), secondStep: false, current: true }],
      '/api/users/devices': [{ id: 'd1', label: 'Chrome on Windows', firstSeenAt: NOW(), lastSeenAt: NOW(), trustedUntil: null, current: true }],
      '/api/users/alerts': [],
      '/api/users/second-step': { enabled: false, backupCodesLeft: 0 },
      '/api/users/alert-email': { available: true, address: 'o***@example.com', verified: true, codeExpiresAt: null },
      '/api/admin/statistics': { totalUsers: 3, activeWallets: 2, revokedWallets: 1, authenticationAttempts: 10, successfulAttempts: 7, failedAttempts: 2, blockedAttempts: 1, invalidSignatureAttempts: 1, replayAttempts: 1, revokedWalletAttempts: 0, securityEventsTotal: 2, securityEventsByType: { REPLAY_ATTEMPT: 1, CSRF_REJECTED: 1 } },
      '/api/admin/users': page([{ id: 'u-2', walletAddress: long, role: 'USER', status: 'ACTIVE', createdAt: NOW(), updatedAt: NOW() }]),
      '/api/admin/actions': page([]),
      '/api/admin/authentication-events': page([{ id: 1, occurredAt: NOW(), walletAddress: long, eventType: 'LOGIN', eventStatus: 'FAILURE', failureReason: 'SIGNATURE_MISMATCH', ipAddress: '10.0.0.1', userAgent: 'Mozilla/5.0 '.repeat(30) }]),
      '/api/admin/security-events': page([{ id: 1, occurredAt: NOW(), walletAddress: long, attackType: url.searchParams.get('attackType') || 'REPLAY_ATTEMPT', description: 'An already consumed authentication challenge was submitted again.', result: 'BLOCKED', ipAddress: '10.0.0.1' }]),
      '/api/admin/login-assessments': page([{ id: 1, walletAddress: long, ipAddress: '10.0.0.1', score: 70, rules: 'NEW_DEVICE', decision: 'ALLOW', assessedAt: NOW(), ruleScore: 70, anomalyScore: 0.64, finalScore: 70, modelVersion: 3, mode: 'SHADOW', topFeatures: ['new_device', 'hour_sin'] }]),
      '/api/admin/risk-model': { version: 3, trainedAt: NOW(), sampleCount: 2000, source: 'SYNTHETIC', mode: 'SHADOW' },
      '/api/admin/emergency-stop': { active: false, blockLogins: false, reason: null, changedBy: null, changedAt: null },
      '/api/admin/wallet-flags': page([]),
      '/api/admin/api-keys': page([]),
      '/api/admin/api-keys/limits': { maxDays: 90 },
      '/api/blockchain/operator': { operatorAddress: long, balanceEth: '9999', balanceLow: false },
    };
    if (request.method() === 'GET' && url.pathname in rows) return json(route, 200, rows[url.pathname]);
    if (request.method() === 'POST' && url.pathname === '/api/users/sessions/end-others') return json(route, 200, { ended: 0 });
    return json(route, 404, { code: 'RESOURCE_NOT_FOUND', message: 'Resource not found.', fieldErrors: [] });
  });
  return state;
}
