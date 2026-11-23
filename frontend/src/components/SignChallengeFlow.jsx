import { useEffect, useRef, useState } from 'react';
import { serverNow } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { ErrorAlert } from './Alert';
import { Spinner } from './Spinner';
import { WalletConnectButton } from './WalletConnectButton';

/** Seconds until `until`, by the server's clock (learned from its responses), never below zero. */
export function secondsLeft(until) {
  return Math.max(0, Math.round((new Date(until).getTime() - serverNow()) / 1000));
}

/** Counts down to the challenge's expiry, then offers a fresh message instead of a request that can no longer succeed. */
function ExpiryNote({ until, onRestart }) {
  const [seconds, setSeconds] = useState(() => secondsLeft(until));
  useEffect(() => {
    const timer = setInterval(() => {
      const left = secondsLeft(until);
      setSeconds(left);
      if (left === 0) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [until]);
  if (seconds === 0) {
    return (
      <div className="hint" role="status">
        <p>This message has expired and can no longer be used. Reject it in MetaMask, then get a new one.</p>
        <button type="button" className="button button-small" onClick={onRestart}>
          Get a new message
        </button>
      </div>
    );
  }
  return (
    <p className="hint">
      This request expires in <span className="mono">{seconds}s</span>. MetaMask should show exactly the message below.
      If it shows anything else, reject it.
    </p>
  );
}

/**
 * The two steps registration and login share: connect the wallet, then sign a one-time message.
 * `onSign(onStep)` runs the sequence and reports its phase; this component shows progress, the exact
 * message being signed, and any error. `children` is the page's own result area.
 *
 * Only one attempt runs at a time (a ref guards it, so a double click sends one request). An attempt the
 * person abandons with "Get a new message" is ignored when it finally settles.
 */
export function SignChallengeFlow({ heading, explanation, actionLabel, submittingLabel, onSign, children }) {
  const { walletAddress } = useAuth();
  const [step, setStep] = useState({ phase: 'idle' }); // idle | challenge | signing | submitting
  const [error, setError] = useState(null);
  const attempt = useRef(0);
  const running = useRef(false);
  const busy = step.phase !== 'idle';

  async function handleSign() {
    if (running.current) return;
    running.current = true;
    const mine = ++attempt.current;
    const current = () => attempt.current === mine;
    setError(null);
    try {
      await onSign((next) => current() && setStep(next), current);
    } catch (e) {
      if (current()) setError(e);
    } finally {
      if (current()) {
        running.current = false;
        setStep({ phase: 'idle' });
      }
    }
  }

  function restart() {
    attempt.current += 1; // the abandoned attempt's result is ignored
    running.current = false;
    setStep({ phase: 'idle' });
    setError(null);
  }

  return (
    <div className="flow">
      <ol className="steps">
        <li className={walletAddress ? 'is-done' : 'is-current'}>
          <h2>Connect your wallet</h2>
          <p className="muted">
            MetaMask asks which account to share. BlockAuth receives the public address only, never your private key
            or recovery phrase.
          </p>
          <WalletConnectButton />
        </li>

        <li className={walletAddress ? 'is-current' : undefined}>
          <h2>{heading}</h2>
          <p className="muted">{explanation}</p>
          <button className="button button-primary" onClick={handleSign} disabled={!walletAddress || busy}>
            {step.phase === 'idle' && actionLabel}
            {step.phase === 'challenge' && <Spinner label="Preparing the message…" />}
            {step.phase === 'signing' && <Spinner label="Waiting for signature…" />}
            {step.phase === 'submitting' && <Spinner label={submittingLabel} />}
          </button>
          {!walletAddress && <p className="hint">Connect your wallet first.</p>}

          {step.phase === 'signing' && (
            <div className="signing">
              <p role="status">Check MetaMask to sign.</p>
              <ExpiryNote until={step.expiresAt} onRestart={restart} />
              <pre className="challenge" aria-label="Message to sign">
                {step.challenge}
              </pre>
            </div>
          )}
          {step.phase === 'submitting' && (
            <p className="hint" role="status">
              Please keep this page open. This can take up to half a minute while the blockchain confirms.
            </p>
          )}
        </li>
      </ol>

      <ErrorAlert error={error} />
      {children}
    </div>
  );
}
