-- Keep the original bid identity after the optional blank PDF attachment expires.
ALTER TABLE bond_purchases ADD COLUMN application_form_origin_id UUID;
UPDATE bond_purchases SET application_form_origin_id = application_form_id
WHERE application_form_id IS NOT NULL;

-- Removing a shared blank form must never cascade to a purchase or confirmation.
ALTER TABLE bond_purchases DROP CONSTRAINT bond_purchases_application_form_id_fkey;
ALTER TABLE bond_purchases ADD CONSTRAINT bond_purchases_application_form_id_fkey
    FOREIGN KEY (application_form_id) REFERENCES bond_application_forms(id) ON DELETE SET NULL;
CREATE INDEX bond_purchases_application_form_idx ON bond_purchases(application_form_id)
    WHERE application_form_id IS NOT NULL;
