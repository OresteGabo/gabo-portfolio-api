ALTER TABLE bond_purchases ADD COLUMN issuance_document_id UUID REFERENCES bond_issuance_documents(id);
ALTER TABLE bond_purchases ADD COLUMN confirmation_method TEXT CHECK(confirmation_method IN ('manual','document'));
ALTER TABLE bond_purchases ADD COLUMN confirmation_document_id UUID REFERENCES bond_uploaded_documents(id) ON DELETE SET NULL;
CREATE INDEX bond_pending_issuance_idx ON bond_purchases(settlement_date,tenor_years) WHERE status='submitted';
