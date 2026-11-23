import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { json, mockFetch } from '../test/fetchMock';
import { query, useApi } from './useApi';
import { useSingleFlight } from './useSingleFlight';

describe('useApi', () => {
  it('never shows the answer for a path the page has moved away from', async () => {
    const resolvers = {};
    mockFetch((url, init) => new Promise((resolve, reject) => {
      resolvers[url] = resolve;
      init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const { result, rerender } = renderHook(({ path }) => useApi(path), { initialProps: { path: '/a?page=0' } });
    expect(result.current.loading).toBe(true);

    rerender({ path: '/a?page=1' });
    resolvers['/a?page=1'](json(200, { page: 1 }));
    await waitFor(() => expect(result.current.data).toEqual({ page: 1 }));
    // The first request was aborted; even if its answer arrived now, it would be ignored.
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('keeps the old data visible while reloading, and reports errors with no stale rows', async () => {
    let answer = json(200, { n: 1 });
    mockFetch(() => answer);
    const { result } = renderHook(() => useApi('/x'));
    await waitFor(() => expect(result.current.data).toEqual({ n: 1 }));

    answer = json(500, { code: 'INTERNAL_ERROR', message: 'boom' });
    act(() => result.current.reload());
    expect(result.current.loading).toBe(true);
    expect(result.current.data).toEqual({ n: 1 });
    await waitFor(() => expect(result.current.error?.code).toBe('INTERNAL_ERROR'));
    expect(result.current.data).toBeNull();
  });

  it('builds query strings without empty values', () => {
    expect(query({ page: 0, size: 20, status: '', role: null })).toBe('?page=0&size=20');
    expect(query({})).toBe('');
  });
});

describe('useSingleFlight', () => {
  it('runs one action at a time: a second call while busy is ignored', async () => {
    const { result } = renderHook(() => useSingleFlight());
    let release;
    let calls = 0;
    const action = () => {
      calls += 1;
      return new Promise((resolve) => {
        release = resolve;
      });
    };

    let first;
    act(() => {
      first = result.current.run('save', action);
      result.current.run('save', action);
      result.current.run('save', action);
    });
    expect(calls).toBe(1);
    expect(result.current.busy).toBe('save');

    await act(async () => {
      release('done');
      expect(await first).toBe('done');
    });
    expect(result.current.busy).toBeNull();
    await act(() => result.current.run('again', async () => 'x'));
    expect(calls).toBe(1);
  });
});
