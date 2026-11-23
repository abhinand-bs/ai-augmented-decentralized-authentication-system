import { afterEach, describe, expect, it, vi } from 'vitest';
import { json, mockFetch } from '../test/fetchMock';
import { api, ApiError, configureApi, serverNow } from './client';

afterEach(() => {
  configureApi({ onSessionEnded: () => {}, onAccessDenied: () => {} });
  vi.useRealTimers();
});

function setCsrfCookie(value = 'token-1') {
  document.cookie = `XSRF-TOKEN=${value}; path=/`;
}

describe('api client', () => {
  it('fetches a CSRF token before the first POST and sends it in the header', async () => {
    const fetch = mockFetch((url) => {
      if (url === '/api/auth/csrf') {
        setCsrfCookie('abc');
        return json(200, { token: 'abc' });
      }
      return json(201, { ok: true });
    });

    await expect(api.post('/api/x', { a: 1 })).resolves.toEqual({ ok: true });

    expect(fetch.mock.calls[0][0]).toBe('/api/auth/csrf');
    expect(fetch.mock.calls[1][1].headers['X-XSRF-TOKEN']).toBe('abc');
    expect(fetch.mock.calls[1][1].headers['Content-Type']).toBe('application/json');
  });

  it('retries a POST exactly once after CSRF_INVALID', async () => {
    setCsrfCookie();
    let posts = 0;
    mockFetch((url) => {
      if (url === '/api/auth/csrf') return json(200, {});
      posts += 1;
      return json(403, { code: 'CSRF_INVALID', message: 'no' });
    });

    await expect(api.post('/api/x')).rejects.toMatchObject({ code: 'CSRF_INVALID', status: 403 });
    expect(posts).toBe(2);
  });

  it('reports a 401 on a protected call as an ended session, but not on the sign-in endpoints', async () => {
    const onSessionEnded = vi.fn();
    configureApi({ onSessionEnded });
    setCsrfCookie();
    mockFetch(() => json(401, { code: 'SESSION_REVOKED', message: 'ended' }));

    await expect(api.get('/api/users/me')).rejects.toBeInstanceOf(ApiError);
    expect(onSessionEnded).toHaveBeenCalledTimes(1);
    await expect(api.post('/api/auth/verify', {})).rejects.toMatchObject({ status: 401 });
    expect(onSessionEnded).toHaveBeenCalledTimes(1);
    await expect(api.post('/api/auth/step-up', {})).rejects.toMatchObject({ status: 401 });
    expect(onSessionEnded).toHaveBeenCalledTimes(2);
    await expect(api.get('/api/users/me', { quiet: true })).rejects.toMatchObject({ status: 401 });
    expect(onSessionEnded).toHaveBeenCalledTimes(2);
  });

  it('turns error bodies into ApiError with Retry-After, reference and field errors', async () => {
    mockFetch(() => json(429, { code: 'RATE_LIMITED', message: 'slow down', errorId: 'e1', fieldErrors: [] },
      { 'Retry-After': '7' }));

    const error = await api.get('/api/x').catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 429, code: 'RATE_LIMITED', retryAfter: 7, errorId: 'e1' });
  });

  it('copes with a non-JSON gateway error and with the network being down', async () => {
    mockFetch(() => new Response('<html>502</html>', { status: 502 }));
    await expect(api.get('/api/x')).rejects.toMatchObject({ status: 502, code: 'UNKNOWN' });

    mockFetch(() => Promise.reject(new TypeError('Failed to fetch')));
    await expect(api.get('/api/x')).rejects.toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('gives up after the time limit instead of spinning forever', async () => {
    vi.useFakeTimers();
    mockFetch((url, init) => new Promise((_, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));

    const pending = api.get('/api/slow', { timeoutMs: 1000 }).catch((e) => e);
    await vi.advanceTimersByTimeAsync(1001);

    expect(await pending).toMatchObject({ status: 0, code: 'TIMEOUT' });
  });

  it('a caller abort rejects with the AbortError, not with an ApiError', async () => {
    mockFetch((url, init) => new Promise((_, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const controller = new AbortController();

    const pending = api.get('/api/x', { signal: controller.signal }).catch((e) => e);
    controller.abort();

    expect((await pending).name).toBe('AbortError');
  });

  it('learns the server clock from the Date header', async () => {
    const serverTime = Date.now() + 3_600_000; // the server is an hour ahead of this browser
    mockFetch(() => json(200, {}, { Date: new Date(serverTime).toUTCString() }));

    await api.get('/api/x');

    expect(Math.abs(serverNow() - serverTime)).toBeLessThan(2000);
  });
});
