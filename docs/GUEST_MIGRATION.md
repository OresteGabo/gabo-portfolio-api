# Opt-in guest account transfer

Phase 7, 2 November 2026. The companion KMP repository documents the user workflow and verification in `docs/guest-mode/PHASE_7.md`. Existing normal authentication, investment tables/calculations and document storage remain in use. This is not an anonymous ingestion API or broker integration.

## Deployment

Apply the additive `V11__guest_migration_receipts.sql` through the normal reviewed Flyway deployment before releasing the client. It adds only `guest_migration_items`, keyed by authenticated owner and stable local source ID, with normalized input/result JSONB and acknowledgment time. No existing financial row is rewritten. No production migration/deployment occurred during implementation.

The table intentionally does not cascade when a holding/document is removed: retries must report removal/conflict instead of recreating deleted investments. Normal account deletion explicitly removes all owner acknowledgments. Sensitive normalized data in this table has the same private-account retention/security boundary as other persisted portfolio data. It must not be exposed by public endpoints, logs or telemetry.

## Endpoints

All routes require normal `/api/bonds/**` account authentication. Existing Origin/JSON checks remain in force; `accountId` is compared with the authenticated owner, never used to authorize another account.

- `GET /api/bonds/guest-migration/status?accountId=...`: owner-only acknowledgments (latest 10,000), target/source IDs, kind, status, acknowledgment time and selected document date. No guest data submitted. Not a financial payload/export endpoint.
- `POST /api/bonds/guest-migration/items`: exact envelope `accountId,sourceId,kind,record,documentIds,confirmed`. `confirmed` must be boolean true. Financial record fields are explicitly allowlisted. Supported kinds: bond, equity, receipt. Source namespace must match kind; sample IDs rejected. Selected proof references must be owned compatible private documents.
- `POST /api/bonds/guest-migration/documents`: exact account/consent/source/digest envelope plus the existing `uploadId,index,count,content,metadata` fields. Only private Bond confirmation / Share contract categories, static labels, empty issuer/instrument name and verified document date. Existing upload limits, PDF validation, private hash deduplication and chunk storage are reused. Full completed bytes must match the supplied SHA-256.

Request limits are 65,536 bytes for financial items and 710,000 bytes per document chunk request. Existing document total-file limits still apply. No anonymous authorization rule or shared-document publication was added.

## Integrity and retries

Each mutation takes the existing account advisory transaction lock. Financial creation/matching and source acknowledgment commit together; a lost response can be retried with the same owner/source/payload. JSONB payload comparison handles numeric/key ordering equivalently. A changed source payload returns 409. Existing targets must still exist and match validated financial facts; removal/change is not silently repaired.

Existing strong purchase keys plus normalized financial, instrument and account-context equality are required for matching. Similar dates/amounts alone do not match. Conflicting/ambiguous candidates, incompatible existing proof or inconsistent receipts require review. Existing financial records/notes are not overwritten. New share notes are validated and retained. Migration bond saves opt out of shared coupon-term contributions; defaults on existing authenticated saves remain unchanged.

Receipts resolve their parent bond only through this owner's successful bond acknowledgment. Validate saved schedule, due date, status, currency, nonnegative whole-unit amount and manual/automatic method. Preserve the original received state/method. The original guest receipt timestamp remains in acknowledgment provenance; the existing receipt table schema is unchanged.

The first selected compatible proof is linked to the existing primary-proof field. All selected proof IDs remain in acknowledgment provenance and selected originals are available in the document library. Later attachment/source edits are explicit review conflicts, not automatic replacements. Chunk retries reuse existing owner/hash deduplication. A completed removed PDF returns conflict rather than a false success.

The server cannot prove that a human checked a box; it enforces authenticated ownership, explicit consent-envelope shape, validation and idempotency. The client supplies the actual review/selection UX, never automatically initiates transfer on login, retains all local originals/records, and stops subsequent requests when the account changes or review closes. Cancelling cannot undo a previously committed request.

## Verification

`./gradlew test`: **70 passed**, zero failures/errors/skips, including seven migration integration tests. Test environment was a disposable Postgres 18 cluster at `127.0.0.1:55479/struct_test_guest_phase7`, not the configured application database. Optional friend/push/web-import settings were explicitly blank in test properties. Synthetic PDF bytes and account fixtures only.

Command:

```sh
JAVA_TOOL_OPTIONS='-Dstruct.test.local-postgres=true -Dstruct.test.database-url=jdbc:postgresql://127.0.0.1:55479/struct_test_guest_phase7' \
GABO_DATABASE_URL='jdbc:postgresql://127.0.0.1:55479/struct_test_guest_phase7' \
GABO_DATABASE_USER=struct_test GABO_DATABASE_PASSWORD= ./gradlew test
```

Tests cover auth/consent/account binding/sample rejection, same-source and strong existing-record deduplication, conflict/non-overwrite/deleted-target behavior, concurrent equity requests, private document ownership/hash/category, resumed chunks and date conflicts, preservation/conflicts of automatic/manual receipts, suppression of shared coupon-term publication, per-account status isolation and account deletion cleanup. No production load test, release certification, infrastructure change or account credential use is claimed.
