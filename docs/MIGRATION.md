# Migration without breaking the existing clients

The Spring API has been implemented locally. The website and installed app still use the existing deployment. Do not change the app origin until the new API is deployed and verified.

## 1. Prepare an isolated database

Use a new local/staging PostgreSQL database. Back up the website database and private PDFs first. Do not put dumps, credentials or PDFs in Git. Restore the database into staging if persistent invited accounts, uploaded documents, current purchases, sessions or market observations already exist there.

Flyway V1 uses the existing web schema and idempotent statements so a restored web database can be adopted. On a database with tables but no Flyway history, explicitly baseline it at version **0** before applying V1; do not baseline at version 1 or skip V1's checks. `spring.flyway.baseline-on-migrate` is intentionally not enabled globally. Review V1 on a restored copy before authorising production migrations. V2 adds the legacy owner document-ID mapping.

Existing password hashes in `bond_users` are supported. Owner/friend credentials remain private environment variables and are not copied by an importer. Account UUIDs, owner_id values and database-backed sessions retain their meaning.

## 2. Import the bundled owner files once

The website additionally has `.data/bonds-portfolio.json`, `.data/equity-portfolio.json` and `private/bonds/bond-documents.json` with its PDFs. Set `GABO_IMPORT_WEB_DIRECTORY` to the absolute web repository path for one startup against the staging database.

The importer reads those files, validates records, preserves purchase IDs/timestamps and legacy document URLs, and commits everything in one transaction. Existing IDs/business identities are skipped. Completion is recorded as `spring-web-seed-v1` in `bond_data_migrations`. Later starts do not reintroduce a purchase deleted after the import. Remove the environment variable after success.

This imports the bundled **owner** seed and PDFs. It does not read website `.env` files, connect to the live Neon database, discover development users in OS temp files, or copy per-user development JSON. Those require a separate explicit export/import if they contain real records that should be retained. Persistent web accounts/documents/history are migrated through the database backup/restore in step 1.

The importer is covered by a synthetic-files integration test. The actual private owner seed is not automatically imported during builds/tests and no real credentials or PDFs are embedded in the JAR.

## 3. Verify staging

Compare owner and invited-account counts, principal/cash totals, share lots, pending statuses, PDF counts and file hashes against the original. Test login/logout, invitation redemption, duplicate import, pending confirmation, PDF downloads, monthly overview and account deletion using staging accounts. Validate official/contract market history separately.

Use `./gradlew test bootJar` with Docker running. Compatibility fixtures in `src/test/resources/web-contract-fixtures.json` were generated directly from the original TypeScript purchase validation, duplicate-key and mobile-overview functions with synthetic data. Password vectors were generated with Node crypto, including Unicode input, modern/legacy scrypt and PBKDF2. The Kotlin tests compare those outputs to catch migration differences.

## 4. Connect clients

- **Mobile:** change the configured API origin to the deployed Spring host after verifying its TLS and health. Existing routes/JSON and PDF chunking remain the same; sign in again on the new hostname. Update native password-sharing domain associations separately if the login origin changes.
- **Website:** existing route handlers and server components currently call Next's server-side libraries directly. They do not automatically use Spring. Replace these with a small Spring API client or a same-origin proxy, forwarding cookies safely and using authenticated requests for server-rendered pages. Browser requests need credentials; allowlist the exact frontend/API origins. Preserve `/create-password` as a website page if you still use it; it is not a backend endpoint.
- **Scheduler:** recreate the daily quote job on the new host or scheduler; configure its bearer secret.

The website adapter and production cutover are separate changes. Keep the existing API serving users until both clients pass staging checks. Avoid simultaneous independent writes to two databases during cutover. Take a final backup, freeze or redirect writes, migrate the final delta, then switch traffic with a rollback plan.

### V9: independent public bond terms and reviewed statement purchases

Additive only: `bond_instruments`, seeded from the existing two public catalog definitions and existing shared issuance records; nullable purchase instrument/source-statement foreign keys and an instrument index. Exact security/settlement matches receive references; financial values and historical snapshots are unchanged. No private customer statements are migration fixtures. New statement creation atomically persists one purchase, original-source link and reconciliation decision, reusing current account locking and uniqueness constraints. Validate the backup and Flyway status before deployment. Application rollback can leave V9 in place; do not drop tables/columns containing new purchases' references. See API.md and companion KMP docs/RECONCILIATION.md for behavior and verification.

Local verification (31 October): all **52 backend tests passed** against a fresh disposable PostgreSQL 18 cluster with Flyway V1–V9, followed by `bootJar`. Tests cover shared parser output saving, unchanged holdings on reconciliation alone, source ownership, independent terms/coupons, exact retries, overlapping documents, changed figures, absent sources, pending applications with different allocated amounts and missing/optional ISIN suffixes. No production datasource was used. Build/test log: `/tmp/struct-statement-backend-tests.log`. Android/iOS and OS-specific recognition evidence is in companion KMP docs/VERIFICATION.md.
