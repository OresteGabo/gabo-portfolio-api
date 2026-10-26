CREATE TABLE price_notifications (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), owner_id TEXT NOT NULL,
 ticker TEXT NOT NULL, market_date DATE NOT NULL, previous_date DATE NOT NULL,
 previous_price NUMERIC(18,6) NOT NULL, closing_price NUMERIC(18,6) NOT NULL,
 shares NUMERIC(20,6) NOT NULL, cash_cost NUMERIC(24,6) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), read_at TIMESTAMPTZ,
 UNIQUE(owner_id,ticker,market_date)
);
CREATE INDEX price_notifications_owner_idx ON price_notifications(owner_id,market_date DESC,id);
CREATE TABLE push_devices (
 id UUID PRIMARY KEY, owner_id TEXT NOT NULL, platform TEXT NOT NULL CHECK(platform IN ('android','ios')),
 token TEXT NOT NULL UNIQUE, environment TEXT NOT NULL CHECK(environment IN ('production','sandbox')),
 language TEXT NOT NULL CHECK(language IN ('en','fr')), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX push_devices_owner_idx ON push_devices(owner_id);
CREATE TABLE price_push_deliveries (
 notification_id UUID REFERENCES price_notifications(id) ON DELETE CASCADE,
 device_id UUID REFERENCES push_devices(id) ON DELETE CASCADE,
 attempts INTEGER NOT NULL DEFAULT 0, sent_at TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(notification_id,device_id)
);
