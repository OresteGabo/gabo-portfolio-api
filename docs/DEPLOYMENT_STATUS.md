# Production deployment — 26 October 2026

- Google Cloud project: `gabo-portfolio-api` (separate from Kaze).
- Cloud Run service: `gabo-portfolio-api`, region `europe-west1`.
- API origin: https://gabo-portfolio-api-639858675564.europe-west1.run.app
- Health: `/actuator/health`.
- Public mobile and website origin remains https://bonds.orestegabo.dev. Vercel's `BONDS_API_ORIGIN` proxies `/api/bonds/*` to Spring before legacy routes. Browser/native cookies and the password-sharing domain stay on the existing origin.
- Request-based CPU billing; zero minimum instances, maximum one instance, 1 CPU, 1 GiB, concurrency 8, timeout 120 seconds.
- Dedicated runtime identity `portfolio-runtime`; database password, owner password hash and cron secret in Secret Manager.
- Neon production branch `plain-wind-27520547` / `production`. JDBC uses pooled queries, direct Flyway migrations, certificate and hostname verification. Four migrations applied.
- The existing Vercel daily cron uses the same private `CRON_SECRET` as Spring. Existing cron path and schedule are retained.

## Data transfer

A private local database backup and private live-website export are retained in ignored `backups/`. The transfer preserves account IDs/password hashes, pending status, purchases, share lots, private PDF contents and historical observations. Local seed data and live website data were merged by identity, with live records authoritative for overlapping IDs. Final post-cutover comparison found no late writes or deletions.

After migration: two invited accounts, five bonds across all accounts (three owner), three share purchases (two owner), five owner PDFs, thirty equity price observations and twenty-one bond market observations. No existing account or PDF was deleted. Old database sessions were intentionally not migrated: users sign in again if an old session is rejected.

## Verification

- Spring Gradle build: 19 tests, zero failures.
- Android shared/core tests: 143 passed (128 shared + 15 core), zero failures; debug APK successful.
- iPhone device build: successful.
- Production HTTPS verification through both the Cloud Run origin and public website: owner session, purchases, shares, overview, all five PDF hashes/downloads, synthetic invited-account login, data isolation, pending confirmation, PDF upload and account deletion. Synthetic accounts and sessions removed afterward.
- Temporary authenticated transfer deployment deleted after cutover.
- Online-connected debug builds installed on the connected iPhone and Android phone, preserving their app data. No localhost override used.

## Future updates

Build and publish a new linux/amd64 container to the Portfolio Artifact Registry, then update only this Cloud Run service, preserving secrets, origins and scaling limits. Run `./gradlew build` before publishing. API source repository: https://github.com/OresteGabo/gabo-portfolio-api. Deployment currently uses an explicit local build and push; GitHub pushes do not automatically deploy the API. Do not put `.env*`, local backups or PDFs in Git or the image.

Keep `BONDS_API_ORIGIN` in Vercel Production. The website's legacy database settings remain for rollback, but normal `/api/bonds/*` traffic is handled by Spring. Do not write independently to the old database after cutover. The original website deployment and database are retained as rollback references; a rollback after new writes requires reconciling those writes.

Minimum instances zero and small limits reduce ongoing costs, but do not guarantee a zero bill. No Kaze service or billing configuration was changed.
