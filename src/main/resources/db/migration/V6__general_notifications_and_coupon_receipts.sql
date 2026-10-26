ALTER TABLE price_notifications RENAME TO notifications;
ALTER TABLE notifications ADD COLUMN kind TEXT NOT NULL DEFAULT 'prices';
ALTER TABLE notifications ADD COLUMN event_key TEXT;
ALTER TABLE notifications ADD COLUMN payload JSONB NOT NULL DEFAULT '{}'::jsonb;
UPDATE notifications SET event_key='price:'||ticker||':'||market_date;
CREATE UNIQUE INDEX notifications_event_idx ON notifications(owner_id,event_key);
ALTER TABLE notifications ALTER COLUMN ticker DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN previous_date DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN previous_price DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN closing_price DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN shares DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN cash_cost DROP NOT NULL;
CREATE TABLE notification_preferences (
 owner_id TEXT PRIMARY KEY, categories JSONB NOT NULL DEFAULT '{}'::jsonb,
 auto_coupon BOOLEAN NOT NULL DEFAULT false, auto_coupon_from DATE,
 monthly_target BIGINT NOT NULL DEFAULT 100000 CHECK(monthly_target BETWEEN 1 AND 1000000000000)
);
CREATE TABLE coupon_receipts (
 owner_id TEXT NOT NULL, purchase_id UUID NOT NULL REFERENCES bond_purchases(id) ON DELETE CASCADE,
 due_date DATE NOT NULL, amount NUMERIC(24,0) NOT NULL,
 received BOOLEAN NOT NULL, method TEXT NOT NULL CHECK(method IN ('manual','automatic')),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(purchase_id,due_date)
);
CREATE INDEX coupon_receipts_owner_idx ON coupon_receipts(owner_id,due_date DESC);
CREATE TABLE account_devices (
 owner_id TEXT NOT NULL, device_id UUID NOT NULL, platform TEXT NOT NULL CHECK(platform IN ('android','ios')),
 first_seen TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(owner_id,device_id)
);
