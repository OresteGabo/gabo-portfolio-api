-- One canonical public report and its terms, shared by authenticated invited users.
-- A reopened auction has a distinct settlement date even when the security code is unchanged.
CREATE TABLE bond_issuance_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_key text NOT NULL UNIQUE,
    sha256 text NOT NULL UNIQUE,
    content text NOT NULL,
    metadata jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
