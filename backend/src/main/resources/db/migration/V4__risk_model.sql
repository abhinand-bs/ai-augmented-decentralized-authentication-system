-- Learned anomaly model beside the trust-score rules. Additive only: every new column is nullable, so rows
-- written before this migration stay valid and mean "no model information".

ALTER TABLE login_assessments
    ADD COLUMN features       JSONB,
    ADD COLUMN rule_score     INTEGER,
    ADD COLUMN anomaly_score  DOUBLE PRECISION,
    ADD COLUMN final_score    INTEGER,
    ADD COLUMN model_version  INTEGER,
    ADD COLUMN mode           VARCHAR(8),
    ADD COLUMN top_features   VARCHAR(80);
ALTER TABLE login_assessments
    ADD CONSTRAINT ck_assess_rule_score    CHECK (rule_score IS NULL OR rule_score BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_assess_final_score   CHECK (final_score IS NULL OR final_score BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_assess_anomaly_score CHECK (anomaly_score IS NULL OR anomaly_score BETWEEN 0 AND 1),
    ADD CONSTRAINT ck_assess_mode          CHECK (mode IS NULL OR mode IN ('OFF', 'SHADOW', 'ENFORCE'));
-- The admin list filters on a minimum anomaly score.
CREATE INDEX ix_assess_anomaly ON login_assessments (anomaly_score DESC, assessed_at DESC);

-- Trained models, newest version in use. The trees are a compact binary encoding written by the backend.
CREATE TABLE risk_models (
    version       INTEGER       PRIMARY KEY,
    trained_at    TIMESTAMPTZ   NOT NULL,
    sample_count  INTEGER       NOT NULL,
    source        VARCHAR(10)   NOT NULL,
    params        JSONB         NOT NULL,
    trees         BYTEA         NOT NULL,
    CONSTRAINT ck_models_version CHECK (version > 0),
    CONSTRAINT ck_models_count   CHECK (sample_count > 0),
    CONSTRAINT ck_models_source  CHECK (source IN ('SYNTHETIC', 'REAL'))
);

-- New signals for the model, read in the same single query as the rules: sign-ins per wallet over time.
CREATE INDEX ix_sessions_user_created ON user_sessions (user_id, created_at DESC);

-- Network of an address for the optional prefix comparison (IPv4 /24, IPv6 /48). NULL for anything that is
-- not an address, so one malformed stored value can never make the trust check fail.
CREATE FUNCTION blockauth_network(address TEXT) RETURNS CIDR
    LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF address IS NULL THEN
        RETURN NULL;
    END IF;
    RETURN network(set_masklen(address::inet, CASE WHEN family(address::inet) = 4 THEN 24 ELSE 48 END));
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;
