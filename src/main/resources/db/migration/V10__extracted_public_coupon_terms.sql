-- Security-level public facts only: no investor, account, private PDF or acquisition references.
-- Auction-specific price/settlement remain in bond_instruments; do not fabricate them here.
CREATE TABLE bond_security_coupon_terms (
    isin TEXT NOT NULL,
    maturity_date DATE NOT NULL,
    terms JSONB NOT NULL,
    conflicted BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY(isin,maturity_date)
);
