# Shared blank application form retention

The authenticated daily job (`GET /api/bonds/rse/daily`, existing scheduler bearer secret) now removes shared blank bond application forms after the corresponding results have been published for **30 elapsed days**. It runs before market collection, so an RSE outage does not prevent cleanup. No separate scheduler, mobile timer or new credential is needed.

## Publication clock and eligibility

The publication clock is the canonical results row's server-generated `bond_issuance_documents.created_at`: the first time the report was published in this service. This is a known publication event, not a guessed official release time, the form-upload date, subscription deadline or migration date. Reuploads keep the original timestamp. A late-imported report therefore conservatively starts its 30 days when first shared here.

A form must be explicitly categorized `Bond application`, have shared visibility, and be past its server-published closing date in Africa/Kigali. A matching `Bond issuance` report must have the same settlement date, maturity and numeric tenor. Missing results or incomplete/mismatched identity retain the form. Eligibility starts at exactly publication + 30 × 24 hours; the next successful daily run performs deletion.

A `submitted` purchase in **any account** postpones cleanup when it explicitly links the form or is a primary Government of Rwanda/RWF application matching settlement date and tenor. The latter protects older/manual applications without a form link, conservatively including unresolved maturity discrepancies. Disabled accounts are not excluded. Publication of public results never marks an application resolved: a user must explicitly resolve/remove their pending record through the existing workflow. Once the last unresolved application is resolved, an already-expired form becomes eligible on the next daily run; another 30-day period is not added.

## What is retained

Only the shared form row (including its base64 PDF content and metadata) is deleted. Purchases, amounts, status, original bid amount/identity, confirmation methods, confirmation PDFs, public results, statements and coupon records are retained.

Flyway V19 backfills the non-document `application_form_origin_id` provenance value and changes the live attachment FK to `ON DELETE SET NULL`, never cascade. New bids persist both values. The API's `applicationFormDocumentId` becomes null after expiry, so refreshed Android/iOS clients stop rendering a PDF download link. The original form UUID remains on the purchase solely to preserve owner-scoped bid retry validation; a retry still rejects a different form or amount. Subscription notifications keep their existing calendar destination and history but lose a retired form's obsolete `documentId`.

Cleanup is transactional. A short `SHARE ROW EXCLUSIVE` lock on `bond_purchases` fences concurrent inserts/updates/deletes while eligibility is checked and form links are cleared; ordinary reads remain available. Concurrent cleanup runs serialize, and repeated runs are idempotent. A failure rolls the transaction back. This job never logs file content or investor amounts.

## Operations and rollout

Deploy the updated backend with Flyway enabled. V19 must succeed before serving the new code. Existing eligible forms can be deleted on the first subsequent authenticated daily run. The production activation and verification are recorded below.

The daily response includes `applicationFormCleanup: {status: "completed", deleted: N}` or `{status: "failed"}`. Cleanup failures are logged and make the daily response HTTP 503 even if market collection succeeds, allowing the scheduler to retry. Existing market collection failures still return 503 independently. Unauthorized requests cannot start cleanup.

Database deletion does not purge previously downloaded user copies or historical database backups. Those follow their existing lifecycle; this feature does not claim physical erasure from backups.

## Verification

Synthetic integration tests use a new disposable local PostgreSQL cluster, never configured production credentials. Coverage includes the 30-day boundary and closing-day fence; missing, mismatched and recently published results; linked and unlinked unresolved applications across accounts; later resolution; concurrent pending writes; intact active/sold/matured purchase rows and confirmation/result files; removal of obsolete notification links; retained bid retries; V19 upgrade from existing form links; and the authenticated daily job during market failure.

Final local verification: `test bootJar` passed with **107 tests, zero failures/errors/skips**, including seven new retention regressions. The PostgreSQL 18 cluster was created solely for this task at loopback port 55486 with a fenced `struct_test_` database. Production settings and credentials were not loaded.

```sh
JAVA_TOOL_OPTIONS='-Dstruct.test.local-postgres=true -Dstruct.test.database-url=jdbc:postgresql://127.0.0.1:55486/struct_test_form_retention' \
GABO_DATABASE_URL='jdbc:postgresql://127.0.0.1:55486/struct_test_form_retention' \
GABO_DATABASE_USER=struct_test GABO_DATABASE_PASSWORD= ./gradlew test bootJar --console=plain
```

## Production activation — 17 November 2026

- Runtime source commit: `ed6b71ec2adb73b5567a7393b4dd315a641c967f` on `feature/guest-mode`, which contains the guest, application-bid and pending-share features already used in production. The older `main` branch was not substituted for that runtime source.
- Linux amd64 image: `europe-west1-docker.pkg.dev/gabo-portfolio-api/portfolio/api@sha256:49643cd2ef5ce648db45115c9bc096ad9e75a3c1f9762e37b5fbaa583f28c0cf`.
- Cloud Run revision: `gabo-portfolio-api-retention-ed6b71e`, project `gabo-portfolio-api`, region `europe-west1`.
- Candidate started with no production traffic. Its health was `UP`; Flyway logs confirmed V19 applied successfully. Runtime/secret-reference configuration and scaling were compared with the prior revision and remained unchanged.
- Promoted to 100% production traffic, removed the temporary review tag, and verified the public API health remained `UP`. The existing Firebase setup tag was retained.
- One authenticated production daily run returned HTTP 200, market status `saved`, and `applicationFormCleanup: {status: "completed", deleted: 0}`. The policy is active; no form was eligible/deleted in that verification run.
- Existing scheduled daily requests were independently observed in production logs on 15 and 16 November. No additional scheduler or secret was created; subsequent calls to the same daily route run the cleanup automatically.

The candidate was built locally with Docker because Cloud Build was disabled in this project. No Cloud Build API was enabled. Production credentials were used only through the existing secret reference to verify the authenticated daily route; they were not printed or committed.
