import { vi } from 'vitest';

/** A minimal EIP-1193 stand-in for MetaMask; `emit` fires wallet events such as accountsChanged. */
export function installWallet({ accounts = ['0x1111111111111111111111111111111111111111'], sign } = {}) {
  const listeners = {};
  const ethereum = {
    request: vi.fn(async ({ method }) => {
      if (method === 'eth_requestAccounts' || method === 'eth_accounts') return accounts;
      if (method === 'personal_sign') return sign ? sign() : `0x${'ab'.repeat(65)}`;
      throw Object.assign(new Error('unsupported'), { code: 4200 });
    }),
    on: vi.fn((event, fn) => {
      (listeners[event] ??= new Set()).add(fn);
    }),
    removeListener: vi.fn((event, fn) => listeners[event]?.delete(fn)),
    emit: (event, value) => listeners[event]?.forEach((fn) => fn(value)),
    listenerCount: (event) => listeners[event]?.size ?? 0,
  };
  window.ethereum = ethereum;
  return ethereum;
}

export function removeWallet() {
  delete window.ethereum;
}
