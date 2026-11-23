import { useState } from 'react';
import { query, useApi } from '../api/useApi';
import { Address } from '../components/Address';
import { AddressField } from '../components/AddressField';
import { ErrorAlert } from '../components/Alert';
import { DataTable } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { SelectFilter } from '../components/SelectFilter';
import { Spinner } from '../components/Spinner';
import { StatusBadge } from '../components/StatusBadge';
import { ATTACK_TYPES, EVENT_TYPES } from '../utils/events';
import { formatDate, isPartialAddress, isValidAddress, label } from '../utils/format';

const DECISIONS = ['ALLOW', 'SECOND_STEP', 'REFUSE'];
const DECISION_KIND = { ALLOW: 'ok', SECOND_STEP: 'warn', REFUSE: 'danger' };
const EVENT_STATUSES = ['SUCCESS', 'FAILURE', 'BLOCKED'];

/**
 * Filter state shared by both logs: the wallet text as typed, and the value actually sent to the
 * server, which is only ever empty or a well-formed address.
 */
function useWalletFilter(onApply) {
  const [text, setText] = useState('');
  const [left, setLeft] = useState(false);
  const invalid = text !== '' && !isValidAddress(text);
  // A half-typed address is only reported once the field is left, or can no longer become valid.
  const showError = invalid && (left || !isPartialAddress(text));
  return {
    walletAddress: invalid ? '' : text,
    field: (
      <AddressField
        label="Wallet"
        value={text}
        onChange={(value) => {
          setText(value);
          setLeft(false);
          onApply();
        }}
        onBlur={() => setLeft(true)}
        error={showError ? 'Not a valid Ethereum address' : null}
      />
    ),
  };
}

function SecurityEvents() {
  const [page, setPage] = useState(0);
  const [attackType, setAttackType] = useState('');
  const wallet = useWalletFilter(() => setPage(0));
  const events = useApi(
    `/api/admin/security-events${query({ page, size: 20, attackType, walletAddress: wallet.walletAddress })}`,
  );
  return (
    <>
      <div className="filters">
        <SelectFilter
          label="Attack type"
          value={attackType}
          onChange={(value) => {
            setAttackType(value);
            setPage(0);
          }}
          options={ATTACK_TYPES}
          allLabel="All attack types"
        />
        {wallet.field}
      </div>
      <DataTable
        caption="Security events, newest first"
        page={events.data}
        loading={events.loading}
        error={events.error}
        onRetry={events.reload}
        empty="No security events recorded."
        columns={[
          { key: 'occurredAt', header: 'Time', render: (row) => formatDate(row.occurredAt) },
          { key: 'attackType', header: 'Attack type', render: (row) => label(row.attackType) },
          { key: 'walletAddress', header: 'Wallet', render: (row) => <Address value={row.walletAddress} /> },
          { key: 'ipAddress', header: 'IP address', render: (row) => <span className="mono">{row.ipAddress}</span> },
          { key: 'result', header: 'Response', render: (row) => <StatusBadge value={row.result} /> },
          { key: 'description', header: 'Description', wrap: true },
        ]}
      />
      <Pagination page={events.data} onChange={setPage} />
    </>
  );
}

function AuthenticationEvents() {
  const [page, setPage] = useState(0);
  const [eventType, setEventType] = useState('');
  const [eventStatus, setEventStatus] = useState('');
  const wallet = useWalletFilter(() => setPage(0));
  const events = useApi(
    `/api/admin/authentication-events${query({ page, size: 20, eventType, eventStatus, walletAddress: wallet.walletAddress })}`,
  );
  const reset = (setter) => (value) => {
    setter(value);
    setPage(0);
  };
  return (
    <>
      <div className="filters">
        <SelectFilter label="Event" value={eventType} onChange={reset(setEventType)} options={EVENT_TYPES} allLabel="All events" />
        <SelectFilter label="Status" value={eventStatus} onChange={reset(setEventStatus)} options={EVENT_STATUSES} allLabel="Any status" />
        {wallet.field}
      </div>
      <DataTable
        caption="Authentication events, newest first"
        page={events.data}
        loading={events.loading}
        error={events.error}
        onRetry={events.reload}
        empty="No authentication events yet."
        columns={[
          { key: 'occurredAt', header: 'Time', render: (row) => formatDate(row.occurredAt) },
          { key: 'eventType', header: 'Event', render: (row) => label(row.eventType) },
          { key: 'eventStatus', header: 'Status', render: (row) => <StatusBadge value={row.eventStatus} /> },
          { key: 'failureReason', header: 'Reason', render: (row) => label(row.failureReason) },
          { key: 'walletAddress', header: 'Wallet', render: (row) => <Address value={row.walletAddress} /> },
          { key: 'ipAddress', header: 'IP address', render: (row) => <span className="mono">{row.ipAddress}</span> },
          // Text sent by the client. React renders it as text, never as markup.
          {
            key: 'userAgent',
            header: 'User agent',
            render: (row) => (
              <span className="clip" title={row.userAgent}>
                {row.userAgent}
              </span>
            ),
          },
        ]}
      />
      <Pagination page={events.data} onChange={setPage} />
    </>
  );
}

const MIN_ANOMALY = ['0.5', '0.6', '0.7', '0.8', '0.9'];

const MODE_TEXT = {
  OFF: 'Off: the model is not consulted.',
  SHADOW: 'Shadow: scores are stored and shown here, but never change a decision.',
  ENFORCE: 'Enforce: an unusual sign-in can lose up to the configured penalty; it can never gain points.',
};

/** Read-only state of the learned model. Nothing in the app can change or retrain it. */
function ModelStatus() {
  const status = useApi('/api/admin/risk-model');
  const data = status.data;
  return (
    <section className="result" aria-labelledby="model-title">
      <h2 id="model-title">Anomaly model</h2>
      <ErrorAlert error={status.error} onRetry={status.reload} />
      {status.loading && !data && <Spinner label="Loading…" />}
      {data && (
        <dl className="facts">
          <div>
            <dt>Mode</dt>
            <dd>
              <StatusBadge value={data.mode} kind={data.mode === 'ENFORCE' ? 'warn' : 'neutral'} /> {MODE_TEXT[data.mode]}
            </dd>
          </div>
          <div>
            <dt>Version</dt>
            <dd className="mono">{data.version ?? 'Not trained yet'}</dd>
          </div>
          <div>
            <dt>Trained</dt>
            <dd>{data.trainedAt ? formatDate(data.trainedAt) : '—'}</dd>
          </div>
          <div>
            <dt>Training data</dt>
            <dd>
              {data.sampleCount
                ? `${data.sampleCount} ${data.source === 'REAL' ? 'recorded ordinary sign-ins' : 'synthetic sign-ins (cold start)'}`
                : '—'}
            </dd>
          </div>
        </dl>
      )}
    </section>
  );
}

function SignInRisk() {
  const [page, setPage] = useState(0);
  const [decision, setDecision] = useState('');
  const [minAnomaly, setMinAnomaly] = useState('');
  const wallet = useWalletFilter(() => setPage(0));
  const assessments = useApi(
    `/api/admin/login-assessments${query({ page, size: 20, decision, minAnomaly, walletAddress: wallet.walletAddress })}`,
  );
  return (
    <>
      <p className="muted">
        Every sign-in whose signature checked out gets a trust score from 100 down: a new device, a new network, a
        flagged wallet, repeated failures from the same address, or a device shared with a flagged wallet each lower it.
        High scores sign in; lower ones need the second step, or are refused when the wallet has none. Beside these
        rules an unsupervised anomaly model rates how unusual the sign-in is (0.5 is ordinary, 1 is a clear outlier).
        It is a risk indicator, never proof of identity.
      </p>
      <ModelStatus />
      <div className="filters">
        <SelectFilter
          label="Decision"
          value={decision}
          onChange={(value) => {
            setDecision(value);
            setPage(0);
          }}
          options={DECISIONS}
          allLabel="Any decision"
        />
        <label className="field">
          Anomaly at least
          <select
            value={minAnomaly}
            onChange={(event) => {
              setMinAnomaly(event.target.value);
              setPage(0);
            }}
          >
            <option value="">Any</option>
            {MIN_ANOMALY.map((v) => (
              <option key={v} value={v}>
                {v}
              </option>
            ))}
          </select>
        </label>
        {wallet.field}
      </div>
      <DataTable
        caption="Sign-in trust scores, newest first"
        page={assessments.data}
        loading={assessments.loading}
        error={assessments.error}
        onRetry={assessments.reload}
        empty="No sign-ins scored yet."
        columns={[
          { key: 'assessedAt', header: 'Time', render: (row) => formatDate(row.assessedAt) },
          { key: 'walletAddress', header: 'Wallet', render: (row) => <Address value={row.walletAddress} /> },
          { key: 'ipAddress', header: 'IP address', render: (row) => <span className="mono">{row.ipAddress}</span> },
          { key: 'ruleScore', header: 'Rules', render: (row) => <span className="mono">{row.ruleScore ?? row.score}</span> },
          {
            key: 'anomalyScore',
            header: 'Anomaly',
            render: (row) => <span className="mono">{row.anomalyScore == null ? '—' : row.anomalyScore.toFixed(2)}</span>,
          },
          { key: 'score', header: 'Final', render: (row) => <span className="mono">{row.finalScore ?? row.score}</span> },
          {
            key: 'topFeatures',
            header: 'Unusual in',
            wrap: true,
            render: (row) => (row.topFeatures?.length ? row.topFeatures.map(label).join(', ') : '—'),
          },
          {
            key: 'modelVersion',
            header: 'Model',
            render: (row) => (row.modelVersion ? <span className="mono">v{row.modelVersion} {label(row.mode)}</span> : '—'),
          },
          {
            key: 'rules',
            header: 'Why',
            wrap: true,
            render: (row) => (row.rules ? row.rules.split(',').map(label).join(', ') : 'Nothing unusual'),
          },
          {
            key: 'decision',
            header: 'Decision',
            render: (row) => <StatusBadge value={row.decision} kind={DECISION_KIND[row.decision]} />,
          },
        ]}
      />
      <Pagination page={assessments.data} onChange={setPage} />
    </>
  );
}

export function SecurityMonitoring() {
  const [log, setLog] = useState('security');
  return (
    <>
      <h1>Security monitoring</h1>
      <p className="lead">
        Security events are attacks the system detected and stopped. Authentication events are every registration,
        login, logout, account-security change and administrator action. Sign-in risk shows how each sign-in was
        scored.
      </p>
      <div className="segmented" role="group" aria-label="Choose a log">
        <button aria-pressed={log === 'security'} onClick={() => setLog('security')}>
          Security events
        </button>
        <button aria-pressed={log === 'auth'} onClick={() => setLog('auth')}>
          Authentication events
        </button>
        <button aria-pressed={log === 'risk'} onClick={() => setLog('risk')}>
          Sign-in risk
        </button>
      </div>
      {log === 'security' && <SecurityEvents />}
      {log === 'auth' && <AuthenticationEvents />}
      {log === 'risk' && <SignInRisk />}
    </>
  );
}
