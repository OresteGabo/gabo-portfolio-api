-- Keep the original pending application and its provenance, but exclude linked estimates from portfolios.
ALTER TABLE bond_purchases ADD COLUMN linked_purchase_id UUID REFERENCES bond_purchases(id) ON DELETE CASCADE;
ALTER TABLE bond_purchases ADD COLUMN linked_at TIMESTAMPTZ;
ALTER TABLE bond_purchases ADD CONSTRAINT bond_link_not_self CHECK (linked_purchase_id IS NULL OR linked_purchase_id <> id);
ALTER TABLE bond_purchases ADD CONSTRAINT bond_link_pending_only CHECK (linked_purchase_id IS NULL OR (status = 'submitted' AND linked_at IS NOT NULL));
CREATE INDEX bond_purchases_linked_idx ON bond_purchases(linked_purchase_id) WHERE linked_purchase_id IS NOT NULL;
