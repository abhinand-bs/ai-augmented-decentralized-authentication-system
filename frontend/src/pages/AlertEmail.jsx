import { useRef, useState } from 'react';
import { api } from '../api/client';
import { withProof } from '../api/stepUp';
import { useApi } from '../api/useApi';
import { Alert, ErrorAlert } from '../components/Alert';
import { Spinner } from '../components/Spinner';
import { StatusBadge } from '../components/StatusBadge';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { formatDate } from '../utils/format';

/**
 * Optional e-mail alerts for sign-ins on a new device. Setting or removing the address asks for a wallet signature;
 * a new address must be confirmed with the code mailed to it before it receives alerts.
 */
export function AlertEmail() {
  const { user } = useAuth();
  const status = useApi('/api/users/alert-email');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const running = useRef(false);
  const data = status.data;

  async function run(name, action) {
    if (running.current) return;
    running.current = true;
    setBusy(name);
    setError(null);
    try {
      await action();
      status.reload();
    } catch (e) {
      setError(e);
    } finally {
      running.current = false;
      setBusy(null);
    }
  }

  const save = (event) => {
    event.preventDefault();
    run('save', async () => {
      const address = email.trim();
      await api.post('/api/users/alert-email', {
        email: address,
        ...(await withProof(user.walletAddress, 'SET_ALERT_EMAIL', { email: address })),
      });
      setEmail('');
    });
  };
  const confirm = (event) => {
    event.preventDefault();
    run('verify', async () => {
      await api.post('/api/users/alert-email/verify', { code: code.trim() });
      setCode('');
    });
  };
  const remove = () =>
    run('remove', async () => api.post('/api/users/alert-email/remove', await withProof(user.walletAddress, 'REMOVE_ALERT_EMAIL')));

  return (
    <section className="result" aria-labelledby="alert-email-title">
      <h2 id="alert-email-title">Alert e-mail</h2>
      <p className="muted">
        Optional. When your wallet signs in on a device it has not used before, BlockAuth can also tell you by e-mail.
        The message contains no links: if it was not you, open BlockAuth yourself and choose “This was not me”.
      </p>
      {status.loading && !data && <Spinner label="Loading…" />}
      <ErrorAlert error={status.error} onRetry={status.reload} />
      {data && !data.available && <Alert kind="info">E-mail alerts are not switched on for this server.</Alert>}
      {data?.available && (
        <>
          {data.address && (
            <p>
              <span className="mono">{data.address}</span>{' '}
              {data.verified ? <StatusBadge kind="ok">Confirmed</StatusBadge> : <StatusBadge kind="warn">Waiting for the code</StatusBadge>}
            </p>
          )}
          {data.address && !data.verified && (
            <form onSubmit={confirm} noValidate>
              <TextField
                label="Code from the e-mail"
                value={code}
                onChange={setCode}
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                hint={data.codeExpiresAt ? `Valid until ${formatDate(data.codeExpiresAt)}.` : undefined}
              />
              <div className="actions">
                <button className="button button-primary" type="submit" disabled={busy !== null || code.trim().length !== 6}>
                  {busy === 'verify' ? <Spinner label="Checking…" /> : 'Confirm address'}
                </button>
              </div>
            </form>
          )}
          <form onSubmit={save} noValidate>
            <TextField
              label={data.address ? 'Use another address' : 'E-mail address'}
              value={email}
              onChange={setEmail}
              type="email"
              autoComplete="email"
              maxLength={254}
            />
            <p className="hint">MetaMask then asks you to sign a confirmation that names this address.</p>
            <div className="actions">
              <button className="button" type="submit" disabled={busy !== null || !email.includes('@')}>
                {busy === 'save' ? <Spinner label="Sending the code…" /> : 'Send a confirmation code'}
              </button>
              {data.address && (
                <button className="button button-danger" type="button" onClick={remove} disabled={busy !== null}>
                  {busy === 'remove' ? <Spinner label="Removing…" /> : 'Stop e-mail alerts'}
                </button>
              )}
            </div>
          </form>
        </>
      )}
      <ErrorAlert error={error} />
    </section>
  );
}
