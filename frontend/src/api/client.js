// All server calls go through here: same-origin cookies, JSON, the CSRF header on POST, a time limit on every
// request, and errors normalized to ApiError.

export class ApiError extends Error {
  constructor(status, code, message, details = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.retryAfter = details.retryAfter ?? null;
    this.errorId = details.errorId ?? null;
    this.fieldErrors = details.fieldErrors ?? [];
  }
}

/** Default time limit. Registration waits for the blockchain receipt (up to 30 s on the server), so it gets more. */
export const DEFAULT_TIMEOUT_MS = 20_000;
export const CHAIN_TIMEOUT_MS = 45_000;

const handlers = { onSessionEnded: () => {}, onAccessDenied: () => {} };

/** AuthContext registers what happens when a protected call answers 401 or 403. */
export function configureApi(next) {
  Object.assign(handlers, next);
}

// Difference between the server's clock and this browser's, from the Date header of the latest response.
// Countdowns use it so that a wrong clock on this computer cannot make a challenge look valid or expired.
let serverOffsetMs = 0;

/** The server's current time in milliseconds, as far as this browser can tell. */
export function serverNow() {
  return Date.now() + serverOffsetMs;
}

function learnServerTime(response) {
  const date = Date.parse(response.headers?.get?.('Date') ?? '');
  if (Number.isFinite(date)) serverOffsetMs = date - Date.now();
}

function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function fetchCsrfToken() {
  await fetch('/api/auth/csrf', { credentials: 'same-origin' });
}

/** Makes sure the browser holds a CSRF token cookie. The server rotates it at login and logout. */
export async function ensureCsrfToken() {
  if (!csrfToken()) await fetchCsrfToken();
}

/**
 * Fetch with a time limit. `signal` (optional) lets the caller abandon the request, for example when a page
 * changes its filter before the previous answer arrived; that rejects with an AbortError, not an ApiError.
 */
async function timedFetch(path, init, timeoutMs, signal) {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const forward = () => controller.abort();
  signal?.addEventListener('abort', forward);
  try {
    return await fetch(path, { ...init, signal: controller.signal });
  } catch (e) {
    if (signal?.aborted) throw e;
    if (timedOut) throw new ApiError(0, 'TIMEOUT', 'The server did not answer in time.');
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server.');
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', forward);
  }
}

async function request(method, path, body, options = {}) {
  const { quiet = false, retried = false, timeoutMs = DEFAULT_TIMEOUT_MS, signal } = options;
  const headers = {};
  if (method === 'POST') {
    await ensureCsrfToken();
    headers['X-XSRF-TOKEN'] = csrfToken() ?? '';
    if (body !== undefined) headers['Content-Type'] = 'application/json';
  }

  const response = await timedFetch(
    path,
    { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) },
    timeoutMs,
    signal,
  );
  learnServerTime(response);

  if (response.ok) {
    return response.status === 204 ? null : response.json();
  }

  // A proxy or gateway may answer with a non-JSON body when the backend is down.
  const error = await response.json().catch(() => ({}));
  if (error.code === 'CSRF_INVALID' && !retried) {
    await fetchCsrfToken();
    return request(method, path, body, { ...options, retried: true });
  }
  // A 401 from the sign-in endpoints means "these credentials were refused", not "your session ended".
  // The step-up endpoint is the exception: it needs a session, so a 401 there means the session ended.
  const signInEndpoint = path.startsWith('/api/auth/') && path !== '/api/auth/step-up';
  if (response.status === 401 && !quiet && !signInEndpoint) {
    handlers.onSessionEnded();
  }
  if (error.code === 'ACCESS_DENIED') {
    handlers.onAccessDenied();
  }
  throw new ApiError(response.status, error.code ?? 'UNKNOWN', error.message ?? 'Something went wrong.', {
    retryAfter: Number(response.headers.get('Retry-After')) || null,
    errorId: error.errorId,
    fieldErrors: error.fieldErrors,
  });
}

export const api = {
  get: (path, options) => request('GET', path, undefined, options),
  post: (path, body, options) => request('POST', path, body, options),
};
