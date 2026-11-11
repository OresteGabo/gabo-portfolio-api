ALTER TABLE bond_purchases ADD COLUMN application_form_id UUID REFERENCES bond_application_forms(id);
ALTER TABLE bond_purchases ADD COLUMN client_bid_id UUID;
ALTER TABLE bond_purchases ADD COLUMN bid_amount NUMERIC(18,2) CHECK (bid_amount IS NULL OR bid_amount > 0);
CREATE UNIQUE INDEX bond_purchases_bid_retry_idx ON bond_purchases(owner_id,client_bid_id) WHERE client_bid_id IS NOT NULL;
