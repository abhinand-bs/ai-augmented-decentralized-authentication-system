import { createContext, startTransition, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { signAndSubmit } from '../api/auth';
import { api, configureApi, ensureCsrfToken } from '../api/client';
import { connectWallet, onAccountsChanged } from '../wallet/metamask';

const AuthContext = createContext(null);

/** How often returning to the tab re-checks the session at most. */
const RECHECK_INTERVAL_MS = 30_000;

// Tabs of this app tell each other that the session changed, so they re-read it from the server. The message
// carries no data at all: it is not storage, and nothing about the session leaves the HttpOnly cookie.
function sessionChannel() {
  return typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('blockauth-session');
}

// The session lives in an HttpOnly cookie the page cannot read. This context mirrors /api/users/me
// in memory only: nothing about the session, role or wallet is ever written to web storage, and the
// role shown here only decides what the navigation offers. The server authorizes every request.
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [walletAddress, setWalletAddress] = useState(null);
  const [notice, setNotice] = useState(null);
  const navigate = useNavigate();
  const userRef = useRef(null);
  const lastCheck = useRef(0);
  const channel = useRef(null);

  useEffect(() => {
    userRef.current = user;
  }, [user]);

  const refresh = useCallback(async () => {
    lastCheck.current = Date.now();
    try {
      const me = await api.get('/api/users/me', { quiet: true });
      setUser(me);
      return me;
    } catch {
      setUser(null);
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  const announce = useCallback(() => channel.current?.postMessage('changed'), []);

  /** Asks MetaMask for the account to use. Proves nothing yet; ownership is proven by signing. */
  const connect = useCallback(async () => {
    const address = (await connectWallet()) ?? null;
    setWalletAddress(address);
    return address;
  }, []);

  /**
   * Signs in with the connected wallet. @returns { user } when the session started, or
   * { secondStepUntil } when the server asks for a verification code first (see completeSecondStep).
   */
  const login = useCallback(
    async (onStep) => {
      const result = await signAndSubmit('login', walletAddress, onStep);
      if (result?.status === 'SECOND_STEP_REQUIRED') return { secondStepUntil: result.expiresAt };
      setNotice(null);
      await ensureCsrfToken();
      const me = await refresh();
      announce();
      return { user: me };
    },
    [walletAddress, refresh, announce],
  );

  /** Finishes a sign-in that is waiting for its second step. Must run in the browser that signed. */
  const completeSecondStep = useCallback(
    async (code) => {
      await api.post('/api/auth/second-step', { code });
      setNotice(null);
      await ensureCsrfToken();
      const me = await refresh();
      announce();
      return me;
    },
    [refresh, announce],
  );

  const logout = useCallback(
    async (message = null) => {
      // From here on a 401 is the result of this logout, not a session that ended elsewhere.
      userRef.current = null;
      try {
        await api.post('/api/auth/logout');
      } catch {
        // The local state is cleared regardless; an unreachable server session expires on its own.
      }
      // One transition for both: React Router applies navigations as transitions, so clearing the user
      // urgently would first render the old protected page without a user, whose guard sends it to /login.
      startTransition(() => {
        setUser(null);
        setNotice(message);
        navigate(message ? '/login' : '/');
      });
      announce();
      ensureCsrfToken().catch(() => {});
    },
    [navigate, announce],
  );
  const logoutRef = useRef(logout);
  useEffect(() => {
    logoutRef.current = logout;
  }, [logout]);

  // Bootstrap: read the session once, and listen for other tabs.
  useEffect(() => {
    ensureCsrfToken()
      .catch(() => {})
      .then(refresh);
    channel.current = sessionChannel();
    if (channel.current) channel.current.onmessage = () => refresh();
    return () => {
      channel.current?.close();
      channel.current = null;
    };
  }, [refresh]);

  // Coming back to the tab after a while: the session may have timed out or been ended elsewhere.
  useEffect(() => {
    const recheck = () => {
      if (document.visibilityState !== 'visible' || !userRef.current) return;
      if (Date.now() - lastCheck.current < RECHECK_INTERVAL_MS) return;
      refresh().then((me) => {
        if (!me) setNotice('Your session has expired or was ended. Please sign in again.');
      });
    };
    document.addEventListener('visibilitychange', recheck);
    window.addEventListener('focus', recheck);
    return () => {
      document.removeEventListener('visibilitychange', recheck);
      window.removeEventListener('focus', recheck);
    };
  }, [refresh]);

  useEffect(() => {
    configureApi({
      onSessionEnded: () => {
        if (!userRef.current) return;
        setUser(null);
        setNotice('Your session has expired or was ended. Please sign in again.');
        navigate('/login');
      },
      // The role may have changed since this page loaded: re-read it so the navigation is accurate.
      onAccessDenied: () => {
        refresh();
      },
    });
    return () => configureApi({ onSessionEnded: () => {}, onAccessDenied: () => {} });
  }, [navigate, refresh]);

  // Registered once for the lifetime of the app. A network (chain) switch in MetaMask needs no handling:
  // signing a message does not depend on the network, and the server checks the registry on its own chain.
  useEffect(
    () =>
      onAccountsChanged((accounts) => {
        const next = accounts[0] ?? null;
        setWalletAddress(next);
        const current = userRef.current;
        // The session belongs to one wallet. If MetaMask now shows another (or none), end it.
        if (current && next?.toLowerCase() !== current.walletAddress.toLowerCase()) {
          userRef.current = null; // a second event before the re-render must not log out twice
          logoutRef.current('Wallet changed — you have been signed out.');
        }
      }),
    [],
  );

  const value = useMemo(
    () => ({ user, loading, walletAddress, notice, connect, login, completeSecondStep, logout, refresh }),
    [user, loading, walletAddress, notice, connect, login, completeSecondStep, logout, refresh],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
