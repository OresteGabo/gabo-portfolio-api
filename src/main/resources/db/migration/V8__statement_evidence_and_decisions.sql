-- Add evidence links and append-only reconciliation decisions to the existing statement items.
-- These are not cash balances, financial transactions or another holdings ledger.
CREATE TABLE bond_statement_sources (
    owner_id TEXT NOT NULL,
    item_id UUID NOT NULL REFERENCES bond_statement_items(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES bond_uploaded_documents(id) ON DELETE CASCADE,
    source_item JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_id,item_id,document_id)
);
CREATE INDEX bond_statement_sources_document_idx ON bond_statement_sources(owner_id,document_id);
CREATE TABLE bond_statement_decisions (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id TEXT NOT NULL,
    item_id UUID NOT NULL REFERENCES bond_statement_items(id) ON DELETE CASCADE,
    decision JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bond_statement_decisions_item_idx ON bond_statement_decisions(owner_id,item_id,sequence);
