import { useState } from 'react';
import { useSingleFlight } from '../api/useSingleFlight';
import { useAuth } from '../context/AuthContext';
import { isMetaMaskAvailable } from '../wallet/metamask';
import { Address } from './Address';
import { Alert, ErrorAlert } from './Alert';
import { Spinner } from './Spinner';
import { StatusBadge } from './StatusBadge';

/** Connects MetaMask and shows which account is connected. Disabled when MetaMask is not installed. */
export function WalletConnectButton() {
  const { walletAddress, connect } = useAuth();
  const { busy, run } = useSingleFlight();
  const connecting = busy !== null;
  const [error, setError] = useState(null);

  function handleConnect() {
    run('connect', async () => {
      setError(null);
      try {
        await connect();
      } catch (e) {
        setError(e);
      }
    });
  }

  if (walletAddress) {
    return (
      <p className="connected">
        <StatusBadge kind="ok">Connected</StatusBadge> <Address value={walletAddress} />
        <span className="muted">To use another account, switch accounts in MetaMask.</span>
      </p>
    );
  }

  const available = isMetaMaskAvailable();
  return (
    <div className="connect">
      {!available && (
        <Alert kind="warn">
          MetaMask is required. Install it from{' '}
          <a href="https://metamask.io" target="_blank" rel="noopener noreferrer">
            metamask.io
          </a>
          , then reload.
        </Alert>
      )}
      <button className="button button-primary" onClick={handleConnect} disabled={!available || connecting}>
        {connecting ? <Spinner label="Waiting for MetaMask…" /> : 'Connect Wallet'}
      </button>
      <ErrorAlert error={error} />
    </div>
  );
}
