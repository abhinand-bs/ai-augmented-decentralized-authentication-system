import { useState } from 'react';

// Set at build time, e.g. VITE_EXPLORER_TX_URL=https://sepolia.etherscan.io/tx/ ; unset for the local chain.
const EXPLORER = import.meta.env.VITE_EXPLORER_TX_URL || '';

/** A transaction hash that wraps instead of overflowing, with a copy button and, when configured, an explorer link. */
export function TxHash({ value }) {
  const [copied, setCopied] = useState(false);
  if (!value) return null;
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }
  return (
    <span className="tx-hash">
      <span className="mono address-full">{value}</span>{' '}
      <button type="button" className="button button-small" onClick={copy} aria-label="Copy transaction hash">
        {copied ? 'Copied' : 'Copy'}
      </button>
      {EXPLORER && /^0x[0-9a-fA-F]{64}$/.test(value) && (
        <>
          {' '}
          <a href={`${EXPLORER}${value}`} target="_blank" rel="noopener noreferrer">
            View on block explorer
          </a>
        </>
      )}
    </span>
  );
}
