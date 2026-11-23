import { vi } from 'vitest';

/** A JSON response like the backend's, with a Date header. */
export function json(status, body, headers = {}) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date().toUTCString(), ...headers },
  });
}

/** Replaces fetch with `handler(url, init)`; returns the mock so tests can inspect calls. */
export function mockFetch(handler) {
  const fn = vi.fn(async (url, init = {}) => handler(String(url), init));
  vi.stubGlobal('fetch', fn);
  return fn;
}
