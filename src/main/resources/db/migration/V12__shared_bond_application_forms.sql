-- Blank application forms belong to the issuance, never to an individual investor.
CREATE TABLE bond_application_forms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_key text NOT NULL UNIQUE,
    sha256 text NOT NULL UNIQUE,
    content text NOT NULL,
    metadata jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
