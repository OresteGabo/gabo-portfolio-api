CREATE TABLE bond_month_reminders (
 owner_id TEXT NOT NULL, month TEXT NOT NULL CHECK(month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(owner_id,month)
);
ALTER TABLE push_devices ADD COLUMN live_start_token TEXT;
ALTER TABLE price_push_deliveries ADD COLUMN live_sent_at TIMESTAMPTZ;
CREATE TABLE bond_live_activities (
 device_id UUID NOT NULL REFERENCES push_devices(id) ON DELETE CASCADE,
 activity_id TEXT NOT NULL, calendar_id TEXT NOT NULL, token TEXT NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(device_id,activity_id)
);

-- Preserve read state and existing delivery IDs when upgrading the earlier subscription milestones.
UPDATE notifications SET
 event_key='bond:'||split_part(event_key,':',2)||':'||CASE split_part(event_key,':',3) WHEN 'close' THEN 'closing' ELSE 'open' END,
 payload=payload||jsonb_build_object('calendarId',split_part(event_key,':',2),'month',left(payload->>'dueDate',7))
 WHERE kind='subscriptions' AND event_key LIKE 'subscription:%';
