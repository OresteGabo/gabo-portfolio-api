ALTER TABLE guest_migration_items DROP CONSTRAINT guest_migration_items_kind_check;
ALTER TABLE guest_migration_items ADD CONSTRAINT guest_migration_items_kind_check CHECK (kind IN ('bond','equity','receipt','document','cash'));
