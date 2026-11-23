-- Opt-in e-mail alerts for sign-ins on a new device. The address is encrypted by the application (AES-GCM, the
-- same key as the second-step secrets); only a verified address receives alerts. The verification code is
-- stored as a keyed hash, never in clear.
CREATE TABLE alert_emails (
    user_id             UUID          PRIMARY KEY REFERENCES users (id),
    address_ciphertext  VARCHAR(500)  NOT NULL,
    verified_at         TIMESTAMPTZ,
    code_hash           VARCHAR(64),
    code_expires_at     TIMESTAMPTZ,
    attempts            INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_alert_emails_code     CHECK ((code_hash IS NULL) = (code_expires_at IS NULL)),
    CONSTRAINT ck_alert_emails_hash     CHECK (code_hash IS NULL OR code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_alert_emails_attempts CHECK (attempts BETWEEN 0 AND 100)
);

ALTER TABLE authentication_events DROP CONSTRAINT ck_auth_events_type;
ALTER TABLE authentication_events ADD CONSTRAINT ck_auth_events_type CHECK (event_type IN ('REGISTRATION',
    'LOGIN', 'LOGOUT', 'WALLET_REVOKED', 'WALLET_ACTIVATED', 'SESSION_TERMINATED', 'SECOND_STEP',
    'SECOND_STEP_ENABLED', 'SECOND_STEP_DISABLED', 'SECOND_STEP_RESET', 'BACKUP_CODES_RENEWED',
    'REMOTE_LOGOUT', 'DEVICE_REMOVED', 'WALLET_FLAGGED', 'WALLET_UNFLAGGED', 'EMERGENCY_STOP_ON',
    'EMERGENCY_STOP_OFF', 'ACTION_REQUESTED', 'ACTION_CANCELLED', 'API_KEY_CREATED', 'API_KEY_REVOKED',
    'ALERT_EMAIL_SET', 'ALERT_EMAIL_VERIFIED', 'ALERT_EMAIL_REMOVED', 'ALERT_EMAIL_SENT', 'ALERT_EMAIL_FAILED',
    'ALERT_EMAIL_LIMITED'));
