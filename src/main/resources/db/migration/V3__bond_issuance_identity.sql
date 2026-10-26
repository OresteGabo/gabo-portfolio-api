-- Public issue facts and their source PDF occupy one document row per account/auction.
-- Settlement distinguishes reopenings of the same security.
CREATE UNIQUE INDEX bond_uploaded_documents_issuance_idx
ON bond_uploaded_documents(owner_id, (metadata->>'issuanceKey'))
WHERE metadata->>'issuanceKey' IS NOT NULL;
