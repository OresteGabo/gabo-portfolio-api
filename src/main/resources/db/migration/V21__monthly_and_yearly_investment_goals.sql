-- Preserve every existing monthly target; the active period remains monthly for old clients.
ALTER TABLE notification_preferences ADD COLUMN goal_period TEXT NOT NULL DEFAULT 'monthly'
    CHECK (goal_period IN ('monthly','yearly'));
ALTER TABLE notification_preferences ADD COLUMN yearly_target BIGINT;
UPDATE notification_preferences SET yearly_target = LEAST(monthly_target * 12, 1000000000000);
ALTER TABLE notification_preferences ALTER COLUMN yearly_target SET DEFAULT 1200000;
ALTER TABLE notification_preferences ALTER COLUMN yearly_target SET NOT NULL;
ALTER TABLE notification_preferences ADD CONSTRAINT yearly_target_range CHECK (yearly_target BETWEEN 1 AND 1000000000000);
