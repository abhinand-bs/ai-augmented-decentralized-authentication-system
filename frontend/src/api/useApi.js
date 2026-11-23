import { useCallback, useEffect, useState } from 'react';
import { api } from './client';

/**
 * GET `path` and re-fetch whenever it changes. Returns { data, error, loading, reload }.
 *
 * Only the answer for the current path is ever shown: a request for a path the page has moved away from
 * (a filter or page change, or a reload) is aborted, and its late answer ignored. While a new answer is on its
 * way the previous data stays visible (marked loading), so the table does not jump.
 */
export function useApi(path) {
  const [version, setVersion] = useState(0);
  const key = `${version}|${path}`;
  const [result, setResult] = useState({ key: null, data: null, error: null });

  useEffect(() => {
    const controller = new AbortController();
    api.get(path, { signal: controller.signal }).then(
      (data) => setResult({ key, data, error: null }),
      (error) => {
        // Rows from another filter or page must not stay on screen under an error about this one.
        if (!controller.signal.aborted) setResult({ key, data: null, error });
      },
    );
    return () => controller.abort();
  }, [path, key]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  const loading = result.key !== key;
  return { data: result.data, error: loading ? null : result.error, loading, reload };
}

/** Builds a query string, skipping empty values. */
export function query(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) search.set(key, value);
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}
