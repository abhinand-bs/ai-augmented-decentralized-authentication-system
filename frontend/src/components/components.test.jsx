import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../context/AuthContext';
import { json, mockFetch } from '../test/fetchMock';
import { installWallet, removeWallet } from '../test/wallet';
import { ConfirmDialog } from './ConfirmDialog';
import { DataTable } from './DataTable';
import { Pagination } from './Pagination';
import { RequireAdmin } from './RequireAdmin';
import { RequireAuth } from './RequireAuth';
import { SelectFilter } from './SelectFilter';
import { SignChallengeFlow } from './SignChallengeFlow';
import { WalletConnectButton } from './WalletConnectButton';

afterEach(() => {
  removeWallet();
  vi.useRealTimers();
});

const USER = { id: 'u1', walletAddress: '0x1111111111111111111111111111111111111111', role: 'USER', status: 'ACTIVE' };

/** The app's providers with a backend answering /api/users/me as `me` (null: not signed in). */
function withAuth(ui, { me = null, path = '/', routes } = {}) {
  mockFetch((url) => {
    if (url === '/api/auth/csrf') {
      document.cookie = 'XSRF-TOKEN=t; path=/';
      return json(200, {});
    }
    if (url === '/api/users/me') return me ? json(200, me) : json(401, { code: 'UNAUTHENTICATED' });
    if (url === '/api/auth/logout') return json(204);
    return json(404, { code: 'RESOURCE_NOT_FOUND' });
  });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>{routes ?? ui}</AuthProvider>
    </MemoryRouter>,
  );
}

describe('ConfirmDialog', () => {
  it('confirms, cancels, closes on Escape, and stays open while busy', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { rerender } = render(
      <ConfirmDialog open title="Revoke?" confirmLabel="Revoke" onConfirm={onConfirm} onCancel={onCancel}>
        body
      </ConfirmDialog>,
    );
    fireEvent.click(screen.getByText('Revoke'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    fireEvent(screen.getByRole('dialog', { hidden: true }), new Event('cancel', { cancelable: true }));
    expect(onCancel).toHaveBeenCalledTimes(1);

    rerender(
      <ConfirmDialog open busy title="Revoke?" confirmLabel="Revoke" onConfirm={onConfirm} onCancel={onCancel}>
        body
      </ConfirmDialog>,
    );
    fireEvent(screen.getByRole('dialog', { hidden: true }), new Event('cancel', { cancelable: true }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Cancel')).toBeDisabled();
  });
});

describe('DataTable, Pagination, SelectFilter', () => {
  const columns = [{ key: 'name', header: 'Name' }];

  it('shows loading, empty, rows and an error with a retry button', () => {
    const onRetry = vi.fn();
    const { rerender } = render(<DataTable columns={columns} loading empty="Nothing" caption="c" />);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    rerender(<DataTable columns={columns} page={{ items: [] }} empty="Nothing" caption="c" />);
    expect(screen.getByText('Nothing')).toBeInTheDocument();
    rerender(<DataTable columns={columns} page={{ items: [{ id: 1, name: 'Ann' }] }} empty="Nothing" caption="c" />);
    expect(screen.getByText('Ann')).toBeInTheDocument();
    rerender(<DataTable columns={columns} error={{ code: 'NETWORK', fieldErrors: [] }} onRetry={onRetry} caption="c" />);
    fireEvent.click(screen.getByText('Try again'));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('pages forward and back, and hides itself when everything fits', () => {
    const onChange = vi.fn();
    const { rerender, container } = render(
      <Pagination page={{ page: 0, totalPages: 1, totalElements: 3 }} onChange={onChange} />,
    );
    expect(container).toBeEmptyDOMElement();
    rerender(<Pagination page={{ page: 1, totalPages: 3, totalElements: 50 }} onChange={onChange} />);
    fireEvent.click(screen.getByText('Next'));
    fireEvent.click(screen.getByText('Previous'));
    expect(onChange.mock.calls).toEqual([[2], [0]]);
    rerender(<Pagination page={{ page: 2, totalPages: 3, totalElements: 50 }} onChange={onChange} />);
    expect(screen.getByText('Next')).toBeDisabled();
  });

  it('offers "all" and readable option labels', () => {
    const onChange = vi.fn();
    render(<SelectFilter label="Status" value="" onChange={onChange} options={['ACTIVE', 'REVOKED']} allLabel="Any" />);
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'REVOKED' } });
    expect(onChange).toHaveBeenCalledWith('REVOKED');
    expect(screen.getByRole('option', { name: 'Revoked' })).toBeInTheDocument();
  });
});

describe('WalletConnectButton', () => {
  it('is disabled with an install hint when MetaMask is missing', async () => {
    withAuth(<WalletConnectButton />);
    expect(await screen.findByText(/MetaMask is required/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Connect Wallet' })).toBeDisabled();
    expect(screen.getByRole('link', { name: 'metamask.io' })).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('asks MetaMask once even when clicked repeatedly', async () => {
    const ethereum = installWallet();
    withAuth(<WalletConnectButton />);
    const button = await screen.findByRole('button', { name: 'Connect Wallet' });
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.click(button);
    expect(await screen.findByText('Connected')).toBeInTheDocument();
    expect(ethereum.request.mock.calls.filter(([r]) => r.method === 'eth_requestAccounts')).toHaveLength(1);
  });
});

describe('SignChallengeFlow', () => {
  function Flow({ onSign }) {
    return (
      <SignChallengeFlow heading="Sign" explanation="e" actionLabel="Sign Challenge" submittingLabel="Verifying…"
        onSign={onSign} />
    );
  }

  async function connected(onSign) {
    installWallet();
    withAuth(<Flow onSign={onSign} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Connect Wallet' }));
    return screen.findByRole('button', { name: 'Sign Challenge' });
  }

  it('starts exactly one attempt for a triple click', async () => {
    let release;
    const onSign = vi.fn(() => new Promise((resolve) => {
      release = resolve;
    }));
    const button = await connected(onSign);
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.click(button);
    expect(onSign).toHaveBeenCalledTimes(1);
    release();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign Challenge' })).toBeEnabled());
  });

  it('counts down on the server clock, stops at zero and offers a new message', async () => {
    const onSign = vi.fn((onStep) => {
      onStep({ phase: 'signing', challenge: 'msg', expiresAt: new Date(Date.now() + 2500).toISOString() });
      return new Promise(() => {}); // MetaMask never answers
    });
    const button = await connected(onSign);
    fireEvent.click(button);
    expect(await screen.findByText(/expires in/)).toBeInTheDocument();
    const restart = await screen.findByRole('button', { name: 'Get a new message' }, { timeout: 4000 });
    fireEvent.click(restart);
    expect(screen.getByRole('button', { name: 'Sign Challenge' })).toBeEnabled();
  });

  it('explains a rejected signature (MetaMask code 4001)', async () => {
    const button = await connected(() => Promise.reject(Object.assign(new Error('rejected'), { code: 4001 })));
    fireEvent.click(button);
    expect(await screen.findByText('You cancelled the request in MetaMask.')).toBeInTheDocument();
  });
});

describe('route guards', () => {
  function Where() {
    const location = useLocation();
    return <p>at {location.pathname} from {location.state?.from ?? '-'}</p>;
  }

  const routes = (
    <Routes>
      <Route path="/login" element={<Where />} />
      <Route element={<RequireAuth />}>
        <Route path="/history" element={<p>history page</p>} />
      </Route>
      <Route element={<RequireAdmin />}>
        <Route path="/admin" element={<p>admin page</p>} />
      </Route>
    </Routes>
  );

  it('shows a loading state, then sends a visitor to login remembering the page', async () => {
    withAuth(null, { path: '/history?x=1', routes });
    expect(screen.getByText('Checking your session…')).toBeInTheDocument();
    expect(await screen.findByText('at /login from /history?x=1')).toBeInTheDocument();
  });

  it('lets a user in, and shows a no-permission page on admin routes', async () => {
    withAuth(null, { me: USER, path: '/history', routes });
    expect(await screen.findByText('history page')).toBeInTheDocument();
  });

  it('refuses a non-admin on an admin route without redirect loops', async () => {
    withAuth(null, { me: USER, path: '/admin', routes });
    expect(await screen.findByRole('heading', { name: 'No permission' })).toBeInTheDocument();
  });
});

describe('AuthContext', () => {
  it('signs out once when MetaMask switches to another account, and registers the listener once', async () => {
    const ethereum = installWallet({ accounts: [USER.walletAddress] });
    const routes = (
      <Routes>
        <Route path="/" element={<p>home</p>} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    );
    const fetch = withAuth(null, { me: USER, routes }) && globalThis.fetch;
    await waitFor(() => expect(fetch.mock.calls.some(([u]) => u === '/api/users/me')).toBe(true));
    await screen.findByText('home');
    expect(ethereum.listenerCount('accountsChanged')).toBe(1);

    ethereum.emit('accountsChanged', ['0x2222222222222222222222222222222222222222']);
    ethereum.emit('accountsChanged', ['0x3333333333333333333333333333333333333333']);

    expect(await screen.findByText('login page')).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([u]) => u === '/api/auth/logout')).toHaveLength(1);
  });

  it('re-checks the session when the tab becomes visible again', async () => {
    withAuth(<p>x</p>, { me: USER });
    const fetch = globalThis.fetch;
    await waitFor(() => expect(fetch.mock.calls.filter(([u]) => u === '/api/users/me')).toHaveLength(1));
    const now = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(now + 60_000);
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await waitFor(() => expect(fetch.mock.calls.filter(([u]) => u === '/api/users/me')).toHaveLength(2));
  });
});
