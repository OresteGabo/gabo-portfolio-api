-- Preserve existing document download URLs when importing bundled owner PDFs.
ALTER TABLE bond_uploaded_documents ADD COLUMN IF NOT EXISTS legacy_id text;
CREATE UNIQUE INDEX IF NOT EXISTS bond_uploaded_documents_legacy_idx ON bond_uploaded_documents(owner_id,legacy_id);
