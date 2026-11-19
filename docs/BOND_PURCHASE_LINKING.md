# Link an existing confirmation to a pending bond application

Implemented and activated with explicit user approval on 2026-11-18.

`POST /api/bonds/purchases/{pendingId}/link-imported` takes `confirmed: true`, `purchaseId`, `pending` and `purchase` reviewed snapshots. The endpoint locks the account, checks both records belong to it, requires a submitted source and a confirmed primary RWF government bond with owned personal confirmation evidence, and verifies the security, settlement, maturity and positive allocation not exceeding the amount applied. Both financial snapshots must still agree. A pending source with coupon receipts or ledger activity is rejected rather than losing that history.

V20 adds nullable `linked_purchase_id` and `linked_at` columns. The original pending application stays stored with its financial/application/bid/statement metadata. Normal portfolio reads, issuance refresh and unresolved-form retention exclude linked estimates. The confirmed purchase, commission, PDF and receipt records are not rewritten. Retrying the same link is idempotent. Read-only detail aliases and bid retry resolution follow the target; ordinary edits to the archived pending ID are rejected. Normal explicit deletion of the target cascades its archived source, while the normal document retention semantics remain.

No client-side delete/new-purchase sequence is used, and no new PDF copy is necessary. No records link automatically when the migration runs.

## Verification

Full `test bootJar` passed against a disposable PostgreSQL 18 cluster at `127.0.0.1:55491/struct_test_bond_linking`; no production credentials or data were loaded. `BondPurchaseLinksIntegrationTests` covers original data/receipts/PDF preservation, partial allocation and differing actual commission, idempotency, wrong-owner and incompatible confirmations, stale snapshots, pending activity, rollback under an injected PostgreSQL failure, and populated-table V20 preservation. The existing full suite was retained. Run command:

```
JAVA_TOOL_OPTIONS='-Dstruct.test.local-postgres=true -Dstruct.test.database-url=jdbc:postgresql://127.0.0.1:55491/struct_test_bond_linking' \
GABO_DATABASE_URL='jdbc:postgresql://127.0.0.1:55491/struct_test_bond_linking' \
GABO_DATABASE_USER=struct_test GABO_DATABASE_PASSWORD= ./gradlew test bootJar --console=plain
```

## Activation and rollback

Activation was explicitly approved. The tested JAR was deployed as a no-traffic candidate, migration and health were validated, then production traffic was promoted. Existing Cloud Run identity, settings, environment and secret references were preserved. No real account link was exercised as a deployment test.

V20 is additive and leaves every existing row unlinked. Before any link is used, restoring the preceding revision's traffic is safe. After links exist, do not roll back to code that does not filter the new link marker: it could display archived estimates again. Keep a link-aware rollback image or fix forward. Never clear links as a rollback shortcut or edit an applied migration.

## Activation evidence

- Full preceding suite: 114 backend tests, no failures/errors/skips, with the disposable PostgreSQL database. The tested JAR was reused unchanged; no tests were represented as rerun during deployment.
- JAR SHA-256: `f03a6bfabca65b0ba96114e270c5fa98e331229d781105711e43e30e23970763`.
- Image: `europe-west1-docker.pkg.dev/gabo-portfolio-api/portfolio/api@sha256:4c593e97787affdd411754eb92aa0454bc20889a8bd403a68ef8a5b41bf84e23` (linux/amd64, user 10001).
- Revision: `gabo-portfolio-api-bondlink-f03a6bfabca6`, serving 100% of default traffic after candidate verification. Previous revision: `gabo-portfolio-api-gh-44177943462-1`.
- V20 applied successfully according to Flyway startup logs. Candidate and public app-backend health both returned UP.
- Runtime/secret/environment comparison passed; deployment labels identify an uncommitted build based on `7e2f7a7`. The temporary candidate tag was removed; existing tags remain.
- Unauthorized link checks: 401 with valid mobile origin and no session; 403 without allowed origin. No private account data was used.

No commit or push was made during live activation. Include this feature before any later source-based rollout; a pre-link-aware image can count archived estimates again after linking is used, as described above.

## Commit review — 2026-11-20

Re-executed the full `test bootJar --rerun-tasks --console=plain` command with the disposable loopback PostgreSQL settings above: 114 tests passed, zero failures/errors/skips, and the JAR built successfully. `git diff --check` passed. No production deployment or real-account mutation was performed during commit preparation.
