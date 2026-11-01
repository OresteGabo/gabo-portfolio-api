# Evidence on legacy investments — 1 November 2026

The legacy website portfolio importer can save parsed financial fields without saving the original PDF. Mobile cannot recover those bytes. The investor must provide the file again.

Existing document endpoints remain authoritative: chunked authenticated PDF storage, owner/hash deduplication and owner-scoped downloads. No migration or second ingestion system is introduced.

## Existing save endpoints

`POST /api/bonds/purchases` with `confirmationDocumentId` and `POST /api/bonds/equities` with `contractDocumentId` can now attach evidence to an exactly matching existing investment:

- Existing account lock and normalized transaction identity select the candidate.
- All normalized financial/identity fields must agree (numeric decimal comparison); optional notes/source/account-reference annotations are preserved.
- The PDF must belong to the caller and have the expected category.
- Existing evidence can be repeated idempotently; a different existing link or conflicting financial detail returns 409.
- Only the missing evidence link/confirmation metadata changes. Original ID, creation time and financial figures are retained.
- A duplicate request without evidence keeps the existing conflict behavior. No order execution, additional holding or automatic statement posting is introduced.

Current mobile code submits the existing save request when legacy equity data has no contract link. File bytes without proven linked financial completion remain reviewable and reuse the existing PDF.

## Public issuance evidence

`GET /purchases` can decorate an old bond with a single shared issuance document matching ISIN, settlement and maturity, even if no issuance link was persisted previously. This read-time association changes neither financial values nor personal allocation status. A public report is distinct from a personal confirmation PDF.

## Verification and rollout

56 backend tests and bootJar passed using a freshly created disposable loopback PostgreSQL cluster. Added integration checks cover repeated evidence attachment to old bonds/shares, original IDs/counts/timestamps, mismatched financial details, cross-account denial and public issuance evidence without personal confirmation. No production data or real financial documents were used.

Deploy this backend before distributing the updated KMP build. Otherwise the old duplicate constraints can reject the evidence-only save. No schema migration is required. No deployment performed in this task. Companion KMP documentation: `docs/TESTER_FEEDBACK_2026_11_01.md` and `docs/VERIFICATION.md`.
