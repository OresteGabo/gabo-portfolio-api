# Cloud Run deployment

Target project: `gabo-portfolio-api`. Always pass `--project=gabo-portfolio-api` to gcloud commands; do not change or replace Kaze resources. The production deployment is complete; see DEPLOYMENT_STATUS.md for verified addresses, migration details and future update instructions.

## Database first

Cloud Run containers are disposable. PostgreSQL and private PDFs must live in an external persistent database, not inside the Cloud Run filesystem. The current service stores PDFs in PostgreSQL, so their base64 content contributes to the database storage allowance.

A separate Neon Free project is one low-usage option. Use a region near the Cloud Run service, for example an available nearby EU region. Keep Portfolio separate from Kaze and the live website database. Google Cloud SQL is another option, but has its own ongoing cost; linking the existing billing account does not make it free.

Save the JDBC hostname/database/user/password privately. Set `GABO_DATABASE_URL=jdbc:postgresql://HOST/DATABASE?sslmode=verify-full` and supply username/password separately. An existing nonempty web database needs the migration review described in MIGRATION.md; use an empty separate database for a new deployment. Do not upload the local .env, PDFs or database dumps as build source.

## Container

`docker build -t gabo-portfolio-api:local .` builds Java 21 source with Gradle and packages the executable JAR in a non-root runtime. The Docker build does not run database integration tests; run `./gradlew test` separately with Docker available. `.dockerignore` and `.gcloudignore` exclude local credentials/data/build caches.

For Cloud Run the image must support Linux amd64. Builds made by Google's build service have the correct target architecture; when building on an Apple Silicon Mac, use `docker buildx build --platform linux/amd64 ...` and publish to the chosen Artifact Registry repository. Do not upload an arm64-only local image.

## Current latency policy — 31 October 2026

The owner approved keeping one service-level minimum instance warm after actual session/startup logs showed 17–21 second waits on scale-from-zero. Current setting is `--min=1`, request-based billing, existing maximum 1 and 1 CPU/1 GiB. Startup CPU boost was already enabled. This avoids routine idle teardown; it does not guarantee uninterrupted instances or prevent database/network delays. Thirty fully idle days have a US$12.96 baseline before shared free-tier discounts, active usage, other services and taxes at the reviewed Tier 1 rates. See [minimum instances](https://docs.cloud.google.com/run/docs/configuring/min-instances), [pricing](https://cloud.google.com/run/pricing) and PERFORMANCE.md. Preserve this service setting during updates. Reverting to `--min=0` is an explicit cost/latency tradeoff, not the current production policy.

## Initial service settings (historical baseline)

- Service: `gabo-portfolio-api`, project explicitly `gabo-portfolio-api`.
- Region: choose deliberately near the database; `europe-west1` is an initial candidate, not a requirement.
- Request-based billing; automatic scaling with minimum instances 0 and maximum instances 1 initially. Limits reduce exposure but are not a hard monetary spending cap.
- Initial resources: 1 CPU, 1 GiB memory, concurrency 8, request timeout 120 seconds. Measure startup/memory before reducing allocation. Client request timeout remains 45 seconds; investigate any cold starts approaching that.
- Set `SPRING_PROFILES_ACTIVE=cloudrun` and the three remote database variables.
- Store `GABO_DATABASE_PASSWORD`, `BONDS_ADMIN_PASSWORD_HASH`, and scheduler secrets in Secret Manager and grant only the dedicated runtime service account access. Configure the owner's email privately.
- Set `GABO_ALLOWED_ORIGINS` to exact intended website and API origins. The generated Cloud Run service URL can be added after it is assigned. Native clients send the API origin itself.
- Public ingress/IAM invocation is needed for ordinary app clients; Spring still requires its own account authentication on all personal-data routes. Google IAM authentication and portfolio login are different layers.
- Default container port is supplied through `PORT`; the API already reads it. Start with a TCP startup probe. `/actuator/health` verifies database/application readiness after startup. No minimum-instance warm-up is required.
- Never activate the `local` profile or disable secure cookies in production.

## Verification and migration

After the empty-database service is healthy, verify login, register a temporary invited test account, and check private-data isolation. Migrate the local portfolio/PDFs through a secure explicit database transfer; the Docker image deliberately excludes those files. The cloudrun profile disables startup seed import, which depends on local web-repository paths and is unsuitable for disposable containers.

Test document hashes/downloads and portfolio totals against local data. Update only development app builds first. Native website-password association and a future custom API domain need separate setup. Do not replace the live website API until its adapters and data cutover are verified.

## Costs

Cloud Run requests/CPU/memory, builds, Artifact Registry, Secret Manager, logs, scheduler jobs, network traffic and the database have separate allowances and charges. The billing account's existing applications share applicable account-level allowances. Minimum 0 prevents deliberately keeping a warm instance but does not guarantee a zero bill. Use project-scoped cost reporting and budget alerts; alerts alone do not cap expenditure. Review the database's free storage and active-compute allowances, including PDF growth.

## Neon CLI setup

`neon.ts`, `package.json` and its lock file manage Neon infrastructure only; the API remains Kotlin/Spring Boot. Build and test it with `./gradlew build`, not `npm run build`. Do not commit `.neon`, `.env*` or `node_modules`.

After `npx neon@latest deploy` pulls `DATABASE_URL` and `DATABASE_URL_UNPOOLED` into the ignored `.env`, run `python3 scripts/prepare-neon-env.py`. It preserves the local PostgreSQL settings and writes separate private `.env.cloudrun` settings for Cloud Run. The generated settings keep JDBC/Hikari for queries and give Flyway the direct connection, with TLS hostname verification using Java’s trust store for both. The Cloud Run profile omits the statement_timeout startup option rejected by Neon’s pooler and bounds JdbcTemplate queries to ten seconds instead. Add the deployed API URL to `GABO_ALLOWED_ORIGINS` before mobile sign-in. Supply these settings through Cloud Run environment variables and Secret Manager; Spring does not load dotenv files automatically.

## Approved monthly Cloud Run spend cap — 31 October 2026

Created and independently read back `Struct Capital - Cloud Run monthly spend cap`, state `CONFIGURED`, **EUR 35 per calendar month** (the user explicitly selected euros because the billing account uses EUR). Scoped only to this project and Cloud Run, gross estimated spend excluding credits, with 50%/80%/100% current-spend alerts and project-level recipients enabled. Required billing-management APIs were enabled for configuration. Existing min=1 and container/deployment settings were preserved.

Unlike an alerts-only budget, this cap can pause new Cloud Run usage and make the backend unavailable until reset/lifted. Enforcement is not instantaneous; overages and ongoing fixed costs remain possible. It excludes database, Vercel, image storage/builds and other service charges, so it is not a total-invoice guarantee. See [Google spend caps](https://docs.cloud.google.com/billing/docs/how-to/budgets-spend-caps) and the companion KMP `docs/UNLOCK_COLD_START.md`.

## Combined €35 target — subsequent 31 October update

The user selected €35 total across providers. The existing Cloud Run enforcement cap was reduced to **€25/month**, state `CONFIGURED`; a separate **€35/month all-services Google project alerts-only budget** was created/read back. Both use EUR gross cost; 50%/80%/100% alerts remain enabled. Added service-level maximum 1 and verified minimum 1, ready revision and complete container template unchanged. The €10 remainder is planning headroom, not an enforced cap on image storage/logs/network/other fees.

Linked production Neon is Free (datasource host matched the audited project); Vercel team is Hobby with no invoice add-ons returned. Existing free limits retained, no plan upgrade. Public repositories use standard hosted GitHub runners; paid account-level storage budgets were not changed. Domain renewals remain separate. Full verified scope and limits: companion KMP `docs/COST_CONTROLS.md`. **There is no guaranteed €35 combined invoice ceiling.** This section supersedes the earlier €35 Cloud Run-only allocation.
