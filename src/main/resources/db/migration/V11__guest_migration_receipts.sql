-- Additive, account-scoped transfer acknowledgments; not a second financial ledger.
-- No FK to polymorphic targets: retain evidence if a transferred holding is later deleted.
CREATE TABLE guest_migration_items (
    owner_id TEXT NOT NULL,
    source_id TEXT NOT NULL CHECK (char_length(source_id) <= 128),
    kind TEXT NOT NULL CHECK (kind IN ('bond','equity','receipt','document')),
    payload JSONB NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_id,source_id)
);
