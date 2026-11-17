-- Device-reported context is informational, never an authentication factor.
ALTER TABLE account_devices ADD COLUMN details jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE account_devices ADD COLUMN last_seen timestamptz;
UPDATE account_devices SET last_seen=first_seen;
ALTER TABLE account_devices ALTER COLUMN last_seen SET DEFAULT now();
ALTER TABLE account_devices ALTER COLUMN last_seen SET NOT NULL;
