# Pending purchases and public issuance results

A BNR public issuance report is shared information about a security, not evidence of a personal allocation.

Spring links pending Government of Rwanda primary-market applications to a unique public issuance using settlement date plus security code, or settlement date/tenor with maturity to resolve ambiguous candidates. Contradictory security codes are never matched by the displayed name. Unknown or ambiguous applications remain pending without an assumed match.

When a public report is saved, every matching pending purchase across accounts receives its coupon rate, first coupon date, payment schedule/frequency, schedule confidence and maturity. Investor amounts, commissions, recorded cash and status stay unchanged. Pending applications created after the report also receive the terms. Existing reports are applied when pending records are read; repeated reads avoid rewriting unchanged terms. Published terms remain separate from application identity and do not enter active investment totals until confirmation.

## Two confirmation paths

- Upload the personal confirmation PDF: its actual allocation and price are reviewed, the PDF is saved privately, and the existing purchase is completed. Its recorded commission is kept once.
- Select Confirm purchase, review the figures, then Yes, confirm purchase: the user explicitly attests that the full applied amount was allocated. Spring rechecks the stored report and recorded cash and updates that same transaction. It records manual confirmation and creates no personal document or fake download entry.

Manual confirmation is offered only when the published price agrees with the recorded cash amount. When they differ, a personal PDF is required; the application does not infer a different face value, accrued payment or allocation. The public issuance PDF remains available as a shared issuance document, distinct from a personal confirmation.

A manually confirmed purchase can later accept its personal PDF, keeping its purchase ID and commission. The source changes from manual to document only if a real private Bond confirmation PDF exists for that account. Other users cannot confirm or attach proof to the purchase.

## Delivery

Apply API migration V7 through the normal API deployment before using the updated mobile build. Commit/push and deployment remain user-controlled. No live migration or deployment is performed by the local implementation.

## Signed reports and later personal PDFs

BNR public reports can contain passive digital-signature widgets. iOS checks PDFKit's signature subtype constant (`/Sig`); Android reads PDF.js's field dictionary correctly. Scripts, widget actions and embedded attachments remain blocked. Signature widgets are accepted as passive PDF structure; the app does not claim to verify their certificate chain or cryptographic authenticity.

Reports can be chosen from Documents, Add purchase or a pending bond's upload action. A public report always opens the shared issuance review, never the personal-allocation review. Matching pending purchases stay pending. Later personal PDFs can reuse missing public coupon/tenor/maturity details from a unique shared report with the same security code and settlement date. The personal PDF must still supply the allocation and executed price; read values are never overwritten with auction totals.

Multipart uploads compare metadata using PostgreSQL JSONB equality, so equivalent number formats do not cause a false resume conflict. A genuinely changed label, terms or part count is still rejected. Regression tests cover the original signed PDF locally, both extraction layouts, two-part storage/download, shared access across accounts, and duplicate uploads.
