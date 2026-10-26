CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS bond_purchases (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  instrument_type TEXT NOT NULL DEFAULT 'treasury' CHECK (
    instrument_type IN ('treasury', 'government', 'corporate', 'municipal', 'other')
  ),
  issuer TEXT NOT NULL DEFAULT 'Government of Rwanda' CHECK (char_length(issuer) BETWEEN 1 AND 160),
  currency CHAR(3) NOT NULL DEFAULT 'RWF',
  market TEXT NOT NULL DEFAULT 'primary' CHECK (market IN ('primary', 'secondary', 'other')),
  purchase_date DATE NOT NULL,
  settlement_date DATE,
  bond_name TEXT NOT NULL CHECK (char_length(bond_name) BETWEEN 1 AND 120),
  isin TEXT NOT NULL DEFAULT '' CHECK (char_length(isin) <= 32),
  tenor_years NUMERIC(5, 2) NOT NULL CHECK (tenor_years > 0 AND tenor_years <= 100),
  face_value NUMERIC(18, 2) NOT NULL CHECK (face_value > 0),
  price_percent NUMERIC(10, 6) NOT NULL DEFAULT 100 CHECK (price_percent > 0 AND price_percent <= 1000),
  accrued_interest_paid NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (accrued_interest_paid >= 0),
  fees_paid NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (fees_paid >= 0),
  amount_invested NUMERIC(18, 2) NOT NULL CHECK (amount_invested > 0),
  coupon_rate NUMERIC(8, 6) NOT NULL CHECK (coupon_rate >= 0 AND coupon_rate <= 1),
  withholding_tax_rate NUMERIC(8, 6) NOT NULL DEFAULT 0.05 CHECK (
    withholding_tax_rate >= 0 AND withholding_tax_rate <= 1
  ),
  maturity_date DATE NOT NULL,
  first_coupon_date DATE,
  coupon_dates JSONB NOT NULL DEFAULT '[]'::jsonb,
  coupon_frequency SMALLINT NOT NULL DEFAULT 2 CHECK (coupon_frequency BETWEEN 1 AND 12),
  schedule_confidence TEXT NOT NULL DEFAULT 'confirmed' CHECK (
    schedule_confidence IN ('confirmed', 'estimated')
  ),
  broker TEXT NOT NULL DEFAULT '' CHECK (char_length(broker) <= 120),
  account_reference TEXT NOT NULL DEFAULT '' CHECK (char_length(account_reference) <= 120),
  source_url TEXT NOT NULL DEFAULT '' CHECK (char_length(source_url) <= 1000),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('submitted', 'active', 'sold', 'matured')),
  notes TEXT NOT NULL DEFAULT '' CHECK (char_length(notes) <= 1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT maturity_after_purchase CHECK (maturity_date > purchase_date)
);

-- Backward-compatible migration for databases created with the original schema.
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS instrument_type TEXT NOT NULL DEFAULT 'treasury';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS issuer TEXT NOT NULL DEFAULT 'Government of Rwanda';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS currency CHAR(3) NOT NULL DEFAULT 'RWF';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS market TEXT NOT NULL DEFAULT 'primary';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS settlement_date DATE;
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS face_value NUMERIC(18, 2);
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS price_percent NUMERIC(10, 6) NOT NULL DEFAULT 100;
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS accrued_interest_paid NUMERIC(18, 2) NOT NULL DEFAULT 0;
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS fees_paid NUMERIC(18, 2) NOT NULL DEFAULT 0;
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS withholding_tax_rate NUMERIC(8, 6) NOT NULL DEFAULT 0.05;
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS first_coupon_date DATE;
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS coupon_dates JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS schedule_confidence TEXT NOT NULL DEFAULT 'confirmed';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS broker TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS account_reference TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS source_url TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'active';

UPDATE bond_purchases
SET
  settlement_date = COALESCE(settlement_date, purchase_date),
  face_value = COALESCE(face_value, amount_invested)
WHERE settlement_date IS NULL OR face_value IS NULL;

ALTER TABLE bond_purchases ALTER COLUMN face_value SET NOT NULL;

ALTER TABLE bond_purchases DROP CONSTRAINT IF EXISTS bond_purchases_tenor_years_check;
ALTER TABLE bond_purchases DROP CONSTRAINT IF EXISTS bond_purchases_amount_invested_check;
ALTER TABLE bond_purchases DROP CONSTRAINT IF EXISTS bond_purchases_coupon_rate_check;
ALTER TABLE bond_purchases ADD CONSTRAINT bond_purchases_tenor_years_check
  CHECK (tenor_years > 0 AND tenor_years <= 100);
ALTER TABLE bond_purchases ADD CONSTRAINT bond_purchases_amount_invested_check
  CHECK (amount_invested > 0);
ALTER TABLE bond_purchases ADD CONSTRAINT bond_purchases_coupon_rate_check
  CHECK (coupon_rate >= 0 AND coupon_rate <= 1);

ALTER TABLE bond_purchases DROP CONSTRAINT IF EXISTS bond_purchases_status_check;
ALTER TABLE bond_purchases ADD CONSTRAINT bond_purchases_status_check
  CHECK (status IN ('submitted', 'active', 'sold', 'matured'));

CREATE INDEX IF NOT EXISTS bond_purchases_maturity_date_idx
  ON bond_purchases (maturity_date);

CREATE INDEX IF NOT EXISTS bond_purchases_purchase_date_idx
  ON bond_purchases (purchase_date DESC);

CREATE TABLE IF NOT EXISTS bond_market_observations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  observed_date DATE NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  source_url TEXT NOT NULL CHECK (char_length(source_url) <= 1000),
  bond_name TEXT NOT NULL CHECK (char_length(bond_name) BETWEEN 1 AND 160),
  normalized_bond_name TEXT NOT NULL CHECK (char_length(normalized_bond_name) BETWEEN 1 AND 160),
  closing_price NUMERIC(10, 6),
  previous_price NUMERIC(10, 6),
  change_text TEXT NOT NULL DEFAULT '' CHECK (char_length(change_text) <= 80),
  volume_text TEXT NOT NULL DEFAULT '' CHECK (char_length(volume_text) <= 80),
  value_text TEXT NOT NULL DEFAULT '' CHECK (char_length(value_text) <= 80),
  raw_row JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (closing_price IS NULL OR closing_price > 0),
  CHECK (previous_price IS NULL OR previous_price > 0)
);

ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS observed_date DATE;
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS source_url TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS bond_name TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS normalized_bond_name TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS closing_price NUMERIC(10, 6);
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS previous_price NUMERIC(10, 6);
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS change_text TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS volume_text TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS value_text TEXT NOT NULL DEFAULT '';
ALTER TABLE bond_market_observations ADD COLUMN IF NOT EXISTS raw_row JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE bond_market_observations
SET observed_date = COALESCE(observed_date, observed_at::date)
WHERE observed_date IS NULL;

ALTER TABLE bond_market_observations ALTER COLUMN observed_date SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS bond_market_observations_daily_trade_idx
  ON bond_market_observations (
    observed_date,
    normalized_bond_name,
    COALESCE(closing_price, -1),
    volume_text,
    value_text
  );

CREATE INDEX IF NOT EXISTS bond_market_observations_recent_idx
  ON bond_market_observations (normalized_bond_name, observed_date DESC, observed_at DESC);

-- User accounts and revocable opaque sessions. Existing purchases belong to the owner.
CREATE TABLE IF NOT EXISTS bond_users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE CHECK (email = lower(email) AND char_length(email) <= 254),
  password_hash TEXT NOT NULL,
  disabled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS bond_sessions (
  token_hash CHAR(64) PRIMARY KEY,
  user_id TEXT NOT NULL,
  credential_version CHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS bond_sessions_expiry_idx ON bond_sessions(expires_at);
CREATE TABLE IF NOT EXISTS bond_registration (
  id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id = true),
  token_hash CHAR(64) NOT NULL,
  email TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS bond_auth_attempts (
  key CHAR(64) PRIMARY KEY,
  count INTEGER NOT NULL,
  reset_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS owner_id TEXT NOT NULL DEFAULT 'owner';
CREATE INDEX IF NOT EXISTS bond_purchases_owner_idx ON bond_purchases(owner_id, purchase_date DESC);

-- Public closing-price observations only. No personal holdings or account IDs.
CREATE TABLE IF NOT EXISTS equity_market_observations (
  ticker TEXT NOT NULL CHECK (ticker ~ '^[A-Z][A-Z0-9]{1,9}$'),
  observed_date DATE NOT NULL,
  closing_price NUMERIC(18, 6) NOT NULL CHECK (closing_price > 0),
  observed_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ,
  PRIMARY KEY (ticker, observed_date)
);

-- A singleton: checking an unchanged RSE page does not create a new market day.
CREATE TABLE IF NOT EXISTS equity_market_collection (
  id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id = true),
  attempted_at TIMESTAMPTZ NOT NULL,
  succeeded_at TIMESTAMPTZ,
  market_date DATE,
  status TEXT NOT NULL CHECK (status IN ('saved', 'database-unavailable', 'unusable-source', 'missing-market-date', 'collection-failed'))
);

-- Keep one duplicate key for each legacy group without deleting any old records.
ALTER TABLE bond_purchases ADD COLUMN IF NOT EXISTS dedup_key TEXT;
WITH keys AS (
  SELECT id, owner_id, concat(
    regexp_replace(upper(bond_name), '[^A-Z0-9]', '', 'g'), '|', currency, '|', market, '|', CASE WHEN market = 'primary' THEN '' ELSE purchase_date::text END, '|', COALESCE(settlement_date, purchase_date), '|',
    face_value::numeric(18,2)::text, '|', price_percent::numeric(10,6)::text
  ) AS identity FROM bond_purchases
), ranked AS (
  SELECT *, row_number() OVER (PARTITION BY owner_id, identity ORDER BY id) AS position FROM keys
)
UPDATE bond_purchases p SET dedup_key = ranked.identity FROM ranked
WHERE p.id = ranked.id AND ranked.position = 1 AND p.dedup_key IS NULL
  AND NOT EXISTS (SELECT 1 FROM bond_purchases existing WHERE existing.owner_id = ranked.owner_id AND existing.dedup_key = ranked.identity);
CREATE UNIQUE INDEX IF NOT EXISTS bond_purchases_duplicate_idx ON bond_purchases(owner_id, dedup_key);

CREATE TABLE IF NOT EXISTS equity_purchases (
  id UUID PRIMARY KEY,
  owner_id TEXT NOT NULL,
  dedup_key TEXT NOT NULL,
  contract_key TEXT,
  report_key TEXT,
  holding JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (owner_id, dedup_key),
  UNIQUE (owner_id, contract_key),
  UNIQUE (owner_id, report_key)
);
CREATE INDEX IF NOT EXISTS equity_purchases_owner_idx ON equity_purchases(owner_id, created_at DESC);
-- Shared historical price projection; private holdings remain account scoped.
CREATE INDEX IF NOT EXISTS equity_purchases_price_history_idx
  ON equity_purchases ((holding->>'ticker'), (holding->>'tradeDate'))
  WHERE holding->>'exchange' = 'RSE' AND holding->>'currency' = 'RWF';

-- Records a one-time import, so later builds cannot restore deleted seed purchases.
CREATE TABLE IF NOT EXISTS bond_data_migrations (
  key TEXT PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Missing-document reminders carry observed statement debits, never investment values.
CREATE TABLE IF NOT EXISTS bond_statement_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id TEXT NOT NULL,
  identity TEXT NOT NULL,
  item JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(owner_id,identity)
);
CREATE INDEX IF NOT EXISTS bond_statement_items_owner_idx ON bond_statement_items(owner_id,created_at DESC);

-- Private PDFs uploaded by the app. Incomplete uploads are hidden and expire after a day.
CREATE TABLE IF NOT EXISTS bond_document_uploads (
  owner_id text NOT NULL,
  upload_id text NOT NULL,
  part_count integer NOT NULL CHECK (part_count BETWEEN 1 AND 20),
  parts jsonb NOT NULL,
  metadata jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (owner_id, upload_id)
);
CREATE TABLE IF NOT EXISTS bond_uploaded_documents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id text NOT NULL,
  sha256 text NOT NULL,
  content text NOT NULL,
  metadata jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_id, sha256)
);
