import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Runs one async action at a time. A second call while one is running is ignored outright: the guard is a ref,
 * so a double click, a triple click or a held Enter key cannot send a second request in the moment before
 * React re-renders the button as disabled. `busy` names the running action (or is null) for the UI.
 */
export function useSingleFlight() {
  const running = useRef(false);
  const mounted = useRef(true);
  const [busy, setBusy] = useState(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const run = useCallback(async (name, action) => {
    if (running.current) return undefined;
    running.current = true;
    setBusy(name);
    try {
      return await action();
    } finally {
      running.current = false;
      if (mounted.current) setBusy(null);
    }
  }, []);

  return { busy, run };
}
