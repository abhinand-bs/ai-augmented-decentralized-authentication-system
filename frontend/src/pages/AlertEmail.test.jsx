import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TxHash } from '../components/TxHash';
import { AuthProvider } from '../context/AuthContext';
import { json, mockFetch } from '../test/fetchMock';
import { installWallet, removeWallet } from '../test/wallet';
import { AlertEmail } from './AlertEmail';

const USER = { id: 'u1', walletAddress: '0x1111111111111111111111111111111111111111', role: 'USER', status: 'ACTIVE' };

afterEach(() => removeWallet());

function setup(statusBody, routes = {}) {
  installWallet({ accounts: [USER.walletAddress] });
  const calls = [];
  mockFetch((url, init) => {
    calls.push([init.method ?? 'GET', url, init.body ? JSON.parse(init.body) : null]);
    if (url === '/api/auth/csrf') {
      document.cookie = 'XSRF-TOKEN=t; path=/';
      return json(200, {});
    }
    if (url === '/api/users/me') return json(200, USER);
    if (url === '/api/auth/step-up') return json(201, { challenge: 'confirm', expiresAt: new Date(Date.now() + 60000).toISOString() });
    if (url in routes) return routes[url](init);
    if (url === '/api/users/alert-email') return json(200, statusBody());
    return json(404, { code: 'RESOURCE_NOT_FOUND' });
  });
  render(
    <MemoryRouter>
      <AuthProvider>
        <AlertEmail />
      </AuthProvider>
    </MemoryRouter>,
  );
  return calls;
}

describe('AlertEmail', () => {
  it('says so when the server has no e-mail alerts', async () => {
    setup(() => ({ available: false, address: null, verified: false, codeExpiresAt: null }));
    expect(await screen.findByText('E-mail alerts are not switched on for this server.')).toBeInTheDocument();
  });

  it('sets an address with a wallet signature naming it, then confirms the mailed code', async () => {
    let state = { available: true, address: null, verified: false, codeExpiresAt: null };
    const calls = setup(() => state, {
      '/api/users/alert-email/verify': () => {
        state = { ...state, verified: true, codeExpiresAt: null };
        return json(200, state);
      },
    });
    const input = await screen.findByLabelText('E-mail address');
    fireEvent.change(input, { target: { value: ' owner@example.com ' } });
    state = { available: true, address: 'o***@example.com', verified: false, codeExpiresAt: new Date().toISOString() };
    fireEvent.click(screen.getByRole('button', { name: 'Send a confirmation code' }));
    await screen.findByText('Waiting for the code');

    const stepUp = calls.find(([m, u]) => m === 'POST' && u === '/api/auth/step-up');
    expect(stepUp[2]).toEqual({ action: 'SET_ALERT_EMAIL', email: 'owner@example.com' });
    const set = calls.find(([m, u]) => m === 'POST' && u === '/api/users/alert-email');
    expect(set[2]).toMatchObject({ email: 'owner@example.com', proof: { challenge: 'confirm' } });

    fireEvent.change(screen.getByLabelText('Code from the e-mail'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm address' }));
    expect(await screen.findByText('Confirmed')).toBeInTheDocument();
  });

  it('removes the address with a signature, once for a double click', async () => {
    let state = { available: true, address: 'o***@example.com', verified: true, codeExpiresAt: null };
    const calls = setup(() => state, {
      '/api/users/alert-email/remove': () => {
        state = { available: true, address: null, verified: false, codeExpiresAt: null };
        return json(204);
      },
    });
    const stop = await screen.findByRole('button', { name: 'Stop e-mail alerts' });
    fireEvent.click(stop);
    fireEvent.click(stop);
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Stop e-mail alerts' })).not.toBeInTheDocument());
    expect(calls.filter(([m, u]) => m === 'POST' && u === '/api/users/alert-email/remove')).toHaveLength(1);
  });

  it('shows a refused code as an error', async () => {
    setup(() => ({ available: true, address: 'o***@example.com', verified: false, codeExpiresAt: null }), {
      '/api/users/alert-email/verify': () => json(403, { code: 'VERIFICATION_CODE_INVALID', message: 'x', fieldErrors: [] }),
    });
    fireEvent.change(await screen.findByLabelText('Code from the e-mail'), { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm address' }));
    expect(await screen.findByText('That code is not right, or it was already used.')).toBeInTheDocument();
  });
});

describe('TxHash', () => {
  it('wraps the hash, copies it, and links to an explorer only when one is configured', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.assign(navigator, { clipboard: { writeText } });
    const hash = `0x${'ab'.repeat(32)}`;
    const { container } = render(<TxHash value={hash} />);
    expect(screen.getByText(hash)).toHaveClass('address-full');
    fireEvent.click(screen.getByRole('button', { name: 'Copy transaction hash' }));
    expect(await screen.findByText('Copied')).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith(hash);
    expect(screen.queryByRole('link')).not.toBeInTheDocument(); // VITE_EXPLORER_TX_URL is not set in tests
    render(<TxHash value={null} />);
    expect(container).not.toBeEmptyDOMElement();
  });
});
