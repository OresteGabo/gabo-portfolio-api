-- Public instrument terms are independent of investor quantities and statement evidence.
CREATE TABLE bond_instruments (
    id TEXT PRIMARY KEY,
    isin TEXT NOT NULL,
    settlement_date DATE NOT NULL,
    terms JSONB NOT NULL,
    UNIQUE(isin,settlement_date)
);
INSERT INTO bond_instruments(id,isin,settlement_date,terms) VALUES
('fxd2-2026-7yr','RWBO7Y000682/MN','2026-07-17','{"isin":"RWBO7Y000682/MN","tenorYears":7,"couponRate":0.115,"settlementDate":"2026-07-17","maturityDate":"2033-07-08","firstCouponDate":"2027-01-15","couponDates":["2027-01-15","2027-07-16","2028-01-14","2028-07-14","2029-01-12","2029-07-13","2030-01-11","2030-07-12","2031-01-10","2031-07-11","2032-01-09","2032-07-09","2033-01-07","2033-07-08"],"pricePercent":100,"bondName":"FXD2/2026/7YR","issuer":"Government of Rwanda","currency":"RWF","instrumentType":"treasury","market":"primary","couponFrequency":2,"scheduleConfidence":"confirmed"}'::jsonb),
('fxd3-2026-10yr','RWBO10Y000683/MN','2026-08-14','{"isin":"RWBO10Y000683/MN","tenorYears":10,"couponRate":0.12,"settlementDate":"2026-08-14","maturityDate":"2036-08-01","firstCouponDate":"2027-02-12","couponDates":["2027-02-12","2027-08-13","2028-02-11","2028-08-11","2029-02-09","2029-08-10","2030-02-08","2030-08-09","2031-02-07","2031-08-08","2032-02-06","2032-08-06","2033-02-04","2033-08-05","2034-02-03","2034-08-04","2035-02-02","2035-08-03","2036-02-01","2036-08-01"],"pricePercent":100,"bondName":"FXD3/2026/10YR","issuer":"Government of Rwanda","currency":"RWF","instrumentType":"treasury","market":"primary","couponFrequency":2,"scheduleConfidence":"confirmed"}'::jsonb);
INSERT INTO bond_instruments(id,isin,settlement_date,terms)
SELECT 'issuance-'||id::text,metadata->'issuance'->>'isin',
    (metadata->'issuance'->>'settlementDate')::date,metadata->'issuance'
FROM bond_issuance_documents ON CONFLICT(isin,settlement_date) DO NOTHING;
ALTER TABLE bond_purchases ADD COLUMN instrument_id TEXT REFERENCES bond_instruments(id);
ALTER TABLE bond_purchases ADD COLUMN statement_document_id UUID REFERENCES bond_uploaded_documents(id) ON DELETE SET NULL;
CREATE INDEX bond_purchases_instrument_idx ON bond_purchases(instrument_id);
-- Backfill references only. Existing financial amounts and historical snapshots stay unchanged.
UPDATE bond_purchases p SET instrument_id=i.id FROM bond_instruments i
WHERE upper(replace(p.isin,'/MN',''))=upper(replace(i.isin,'/MN','')) AND p.settlement_date=i.settlement_date;
