// A stand-in for MetaMask in the browser. `personal_sign` signs for real with a known test key, in Node (ethers,
// a test-only devDependency), so the same stub works against the real backend. Test hooks: emitWallet() fires
// wallet events such as accountsChanged.
import { getBytes, keccak256, toUtf8Bytes, Wallet } from 'ethers';

/** A deterministic test key derived from a label, so no key-shaped literal sits in the repository. */
export function testWallet(label) {
  return new Wallet(keccak256(toUtf8Bytes(`blockauth e2e ${label}`)));
}

/**
 * @param options.reject a MetaMask error code to answer personal_sign with (4001 rejected, -32002 already open)
 * @param options.hang never answer personal_sign (a popup left open)
 */
export async function installWallet(page, wallet, options = {}) {
  await page.exposeFunction('__e2eSign', async (hexMessage) => wallet.signMessage(getBytes(hexMessage)));
  await page.addInitScript(({ address, reject, hang }) => {
    const listeners = {};
    let accounts = [address];
    window.__e2eWallet = { requests: [] };
    window.ethereum = {
      isMetaMask: true,
      async request({ method, params }) {
        window.__e2eWallet.requests.push(method);
        if (method === 'eth_requestAccounts' || method === 'eth_accounts') return accounts;
        if (method === 'wallet_switchEthereumChain') return null;
        if (method === 'personal_sign') {
          if (hang) return new Promise(() => {});
          if (reject) throw Object.assign(new Error('rejected'), { code: reject });
          return window.__e2eSign(params[0]);
        }
        throw Object.assign(new Error(`unsupported ${method}`), { code: 4200 });
      },
      on(event, fn) {
        (listeners[event] ??= new Set()).add(fn);
      },
      removeListener(event, fn) {
        listeners[event]?.delete(fn);
      },
    };
    window.__e2eEmit = (event, value) => {
      if (event === 'accountsChanged') accounts = value;
      listeners[event]?.forEach((fn) => fn(value));
    };
    window.__e2eListenerCount = (event) => listeners[event]?.size ?? 0;
  }, { address: wallet.address, reject: options.reject ?? null, hang: options.hang ?? false });
}
