CREATE TABLE investment_cash_ledger (
 id UUID PRIMARY KEY,
 owner_id TEXT NOT NULL,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX investment_cash_ledger_owner_idx ON investment_cash_ledger(owner_id,created_at);
CREATE INDEX investment_cash_ledger_holding_idx ON investment_cash_ledger(owner_id,(payload->>'holdingId'));
