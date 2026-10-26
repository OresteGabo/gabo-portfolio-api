# Gabo Portfolio API

Standalone Kotlin / Spring Boot backend for the Gabo Portfolio website and Android/iOS app. The API retains the website's `/api/bonds` URLs, JSON names, opaque session cookies, twelve-hour sessions, invitation-only registration and owner/invited-account separation.

## Run locally

Requires JDK 21 and Docker Desktop (or a PostgreSQL server).

1. Copy `.env.example` to a private local `.env` and set the database password, owner email and existing password hash. Do not commit `.env`. Spring does not load it automatically; export the variables in your IDE run configuration or shell.
2. Start PostgreSQL: `docker compose up -d postgres`. Compose reads `.env` for its database password.
The local database uses host port **55432** (PostgreSQL stays on port 5432 inside Docker), avoiding conflicts with other projects.

3. Run `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` with those variables exported. For a shell-compatible `.env`, use `set -a`, `. ./.env`, then `set +a`. Quote password hashes in single quotes if they contain `$`.
4. Check `http://localhost:8080/actuator/health`.

A database is required. There is deliberately no JSON-file persistence fallback in this service. PostgreSQL is authoritative for the owner and invited users. Flyway manages schema changes; Hibernate never creates or updates tables.

To generate an owner password hash, run `sh scripts/hash-password.sh`. Input is hidden and not placed in command arguments. Existing Node modern/legacy scrypt and browser PBKDF2 hashes also work. Keep the actual hash private.

`./gradlew test bootJar` runs isolated PostgreSQL integration tests through Testcontainers and produces `build/libs/gabo-portfolio-api-0.0.1-SNAPSHOT.jar`. Tests use synthetic records and do not connect to the website database or import real PDFs.

To verify owner login interactively, run `python3 scripts/check-login.py`. Enter the original password when prompted. The script checks login and session, then signs out its temporary session; it never prints the password or cookie.

## API compatibility

See [docs/API.md](docs/API.md) for the route inventory. Clients must send `Origin` on mutations, `Content-Type: application/json` for JSON bodies and the session cookie returned by login. Private responses use `Cache-Control: private, no-store`.

Local HTTP uses profile `local` and `bonds_session_v2`; production defaults to HTTPS-only `__Host-bonds_session`. Cookies are HttpOnly and SameSite=Strict. Add exact website and API origins to `GABO_ALLOWED_ORIGINS`, for example `https://bonds.orestegabo.dev,https://api.orestegabo.dev`. The mobile app sends its configured server origin. No wildcard CORS is used. The website and API should share the same site for Strict cookies, or use a same-origin website proxy.

Changing the API hostname requires signing in again: host-only cookies do not move between hosts. Existing database session rows are compatible, but stateless JSON-mode owner sessions are not.

The Spring backend does not depend on Next.js, Node or the website source at runtime. It serves JSON and authenticated PDFs, not web pages. PDF extraction/OCR, visual reports, calendar rendering, simulators and charts currently run in the web/mobile clients and remain there. The API validates reviewed fields, persists them and stores the PDF bytes. Uploading a PDF alone does not confirm a purchase: the existing client uploads chunks and then PUTs the reviewed confirmation to the existing pending purchase ID.

## Storage and migration

See [docs/MIGRATION.md](docs/MIGRATION.md). Never point a test run or initial deployment at the production database. Back up and test a restored copy first. No production cutover is performed by creating this project.

## Deployment

- Run the executable JAR on a JVM host or container with Java 21 and HTTPS at the reverse proxy.
- Configure `GABO_DATABASE_URL` as a JDBC URL, for example `jdbc:postgresql://host/database?sslmode=verify-full`, plus `GABO_DATABASE_USER` and `GABO_DATABASE_PASSWORD`. Node's `postgresql://user:password@host/database` URL must be converted; do not paste credentials into committed configuration.
- Set the owner email/hash and allowlisted origins. Leave `GABO_COOKIE_SECURE=true` in production.
- The API does not trust arbitrary forwarding headers for rate limiting. Configure a trusted reverse proxy and Spring/Tomcat forwarding handling deliberately if deploying multiple users behind it.
- Expose only health publicly. Actuator internals, account data and PDFs are not public endpoints. Do not log request bodies or cookies at the proxy.
- Schedule a daily authenticated GET to `/api/bonds/rse/daily` with `Authorization: Bearer <CRON_SECRET>`. Vercel's existing cron will not automatically run on a new host. Snapshot uses a separate `BONDS_MARKET_SNAPSHOT_SECRET`.
- RSE reads use fixed upstream URLs, request/body limits and short timeouts. Quotes are cached for fifteen minutes; failures can use a clearly marked stale quote for up to one day. Missing historical dates stay missing; contract execution prices remain distinct from official closing prices.
- Private PDFs currently remain in PostgreSQL, matching the website. Plan database capacity and private backups; object storage can be added later without changing client URLs.
- Choose and document the actual storage locations, backups and hosting providers before processing invited users' data in the new deployment.
