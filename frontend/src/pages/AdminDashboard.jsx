import { useApi } from '../api/useApi';
import { Alert, ErrorAlert } from '../components/Alert';
import { Spinner } from '../components/Spinner';
import { StatCard } from '../components/StatCard';
import { label } from '../utils/format';

/** Warns before registry writes start failing because the operator account cannot pay for gas. */
function OperatorBalance() {
  const operator = useApi('/api/blockchain/operator');
  if (!operator.data?.balanceLow) return null;
  return (
    <Alert kind="warn">
      The operator account&apos;s balance is low ({operator.data.balanceEth} ETH). Registrations and revocations fail
      once it cannot pay for gas: fund <span className="mono address-full">{operator.data.operatorAddress}</span>.
    </Alert>
  );
}

export function AdminDashboard() {
  const { data, error, loading, reload } = useApi('/api/admin/statistics');
  const byType = data ? Object.entries(data.securityEventsByType).sort((a, b) => b[1] - a[1]) : [];
  const largest = Math.max(1, ...byType.map(([, total]) => total));

  return (
    <>
      <div className="title-row">
        <h1>Admin dashboard</h1>
        <button className="button" onClick={reload} disabled={loading}>
          {loading && data ? <Spinner label="Refreshing…" /> : 'Refresh'}
        </button>
      </div>
      <p className="lead">Totals since the system was set up.</p>
      <OperatorBalance />
      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <Spinner label="Loading statistics…" />}
      {data && (
        <>
          <h2>Wallets</h2>
          <dl className="stats">
            <StatCard label="Users" value={data.totalUsers} />
            <StatCard label="Active wallets" value={data.activeWallets} />
            <StatCard label="Revoked wallets" value={data.revokedWallets} />
          </dl>

          <h2>Login attempts</h2>
          <dl className="stats">
            <StatCard label="Authentication attempts" value={data.authenticationAttempts} />
            <StatCard label="Successful" value={data.successfulAttempts} />
            <StatCard label="Failed" value={data.failedAttempts} />
            <StatCard label="Blocked by rate limit" value={data.blockedAttempts} />
          </dl>

          <h2>Why logins failed</h2>
          <dl className="stats">
            <StatCard label="Invalid signature" value={data.invalidSignatureAttempts} />
            <StatCard label="Replayed message" value={data.replayAttempts} />
            <StatCard label="Revoked wallet" value={data.revokedWalletAttempts} />
          </dl>

          <h2>Security events</h2>
          <dl className="stats">
            <StatCard label="Detected and blocked" value={data.securityEventsTotal} />
          </dl>
          {data.securityEventsTotal === 0 ? (
            <p className="muted">No security events recorded.</p>
          ) : (
            <ul className="bars" aria-label="Security events by type">
              {byType.map(([type, total]) => (
                <li key={type}>
                  <span id={`bar-${type}`}>{label(type)}</span>
                  <progress value={total} max={largest} aria-labelledby={`bar-${type}`} />
                  <span className="mono">{total}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  );
}
