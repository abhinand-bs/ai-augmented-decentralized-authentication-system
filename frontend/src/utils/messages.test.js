import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { formatDate, label, shortAddress } from './format';
import { hintFor, MESSAGES, messageFor } from './messages';

// The backend's error catalog, read from its source so a new code cannot be added without a message here.
const catalog = readFileSync(resolve(__dirname, '../../../backend/src/main/java/com/blockauth/common/ErrorCode.java'),
  'utf8');
const codes = [...catalog.matchAll(/^\s+([A-Z_]+)\((\d{3}), Level\.[A-Z]+, "([^"]*)"\)/gm)].map((m) => ({
  code: m[1], status: Number(m[2]), serverMessage: m[3],
}));

describe('messages', () => {
  it('found the backend catalog', () => {
    expect(codes.length).toBeGreaterThanOrEqual(51);
  });

  it.each(codes)('$code has a plain-language message', ({ code, status, serverMessage }) => {
    // Its own entry, or one of the status-wide messages (429: wait; 502 and up: temporarily unavailable).
    expect(code in MESSAGES || status === 429 || status >= 502, `no message for ${code}`).toBe(true);
    const text = messageFor(new ApiError(status, code, serverMessage));
    expect(text).toBeTruthy();
    expect(text).not.toBe('Something went wrong.');
    expect(text).not.toMatch(/[A-Z]{3,}_[A-Z]/); // never the raw code
  });

  it('explains 429 with the wait time, and wallet errors from MetaMask', () => {
    expect(messageFor(new ApiError(429, 'RATE_LIMITED', 'x', { retryAfter: 12 }))).toMatch(/12 seconds/);
    expect(messageFor({ code: 4001 })).toMatch(/cancelled/);
    expect(messageFor({ code: -32002 })).toMatch(/already open/);
    expect(hintFor({ code: 4001 })).toMatch(/Nothing was signed/);
    expect(messageFor(new ApiError(0, 'TIMEOUT', 'x'))).toMatch(/in time/);
  });

  it('formats addresses, labels and dates', () => {
    expect(shortAddress('0x1234567890abcdef1234567890abcdef12345678')).toBe('0x1234…5678');
    expect(label('CSRF_REJECTED')).toBe('CSRF rejected');
    expect(formatDate(null)).toBe('');
    expect(formatDate('not a date')).toBe('');
    expect(formatDate('2026-11-23T10:00:00Z')).toMatch(/2026/);
  });
});
