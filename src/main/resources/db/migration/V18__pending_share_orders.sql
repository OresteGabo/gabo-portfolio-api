-- Order requests are separate from executed purchases and therefore excluded from all valuations.
CREATE TABLE share_orders (
    owner_id TEXT NOT NULL,
    id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending','cancelled','executed')),
    data JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id,id)
);
CREATE INDEX share_orders_pending_owner_idx ON share_orders(owner_id,updated_at DESC) WHERE status='pending';
