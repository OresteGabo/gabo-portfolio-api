# Notifications

The Stocks navigation item shows the server unread count. Its app-bar bell opens a paginated inbox. Opening an alert expands its detail and marks that alert read only after the server acknowledges it. Financial text uses the existing number-hiding and capture-redaction components. English and French share the same implementation.

Spring creates alerts after the scheduled RSE collection, for active RWF/RSE holdings owned by each account on the market date. The comparison uses the preceding recorded close, which may be earlier than yesterday. The first observation and unchanged prices create no alerts. Repeated collections create no additional alert for the same account/company/market date. Holdings and cash cost are captured at that time, so later purchases do not rewrite historical impact. Results are estimates, not realised profit or sale proceeds.

## Notification categories and coupon receipts

Settings > Notifications > Notification preferences controls share prices, coupons, maturities, subscription windows, pending purchases, missing documents, monthly targets, report exports and native-device sign-ins. Preferences and the monthly target are stored by Spring per account and synchronized between devices.

- Coupon reminders start within three days of the due date; a due reminder remains actionable for thirty days. Confirm received records a manual receipt. Mark not received reverses it and prevents automatic marking from overriding that decision.
- Auto-confirm coupons is off by default. Turning it on establishes a start date in Rwanda time. Only coupons due from that date onward are marked automatically, including catch-up after a missed run. Earlier schedules are not retroactively treated as receipts. Automatic marks are explicitly labelled and reversible; they are not bank verification.
- Bond maturity reminders start thirty days ahead. Subscription reminders use the published opening/closing window, with a separate closing reminder in its final two days. Keep the API's issuance-calendar.json updated when new official calendars are published; no future windows are invented.
- Pending purchases are reminded after at least three days and once settlement is due. Their action opens the confirmation file picker directly. Missing-document reminders use statement reconciliation and resolve when matching purchases are saved.
- A monthly target milestone counts confirmed contributions, excluding pending purchases and market-price gains. It is emitted once per month.
- A report notice is created only after the user successfully saves the locally generated PDF. The report itself is not hosted by this endpoint. Coupon receipts, including manual/automatic status, are included in the PDF and its structured archive.
- New-device alerts identify a new native app installation after an earlier installation is known. They do not infer location or fingerprint a browser. The initial known installation establishes a baseline without an alert.

The existing daily cron processes calendar/account reminders even if RSE fetching fails. Opening the inbox also synchronizes reminders. Event keys prevent duplicates; the upcoming and due coupon reminders are distinct stages. Scheduled coupon totals continue to be labelled scheduled; confirmed receipts are shown separately.

## Activate after committing

Nothing is deployed by this change. Push the API commit first; its existing deployment pipeline applies V5 and V6 automatically. Then build/install the mobile app. Older APIs do not have the notification endpoints; the inbox explains this without blocking the portfolio.

In-app notifications work on both platforms without push-provider credentials. Push permissions are requested only from the inbox/settings action. Denying them leaves in-app notifications usable.

### Android

1. Add Firebase to the existing `gabo-portfolio-api` Google Cloud project (keep Kaze separate). Register Android application `dev.orestegabo.portfolio`.
2. Place the downloaded client configuration at `app/androidApp/google-services.json`. This local configuration is ignored by Git. The Google Services plugin runs only when it exists, so normal builds still work without Firebase configuration.
3. Enable the Firebase Cloud Messaging API. Configure Spring's `GABO_PUSH_FIREBASE_PROJECT=gabo-portfolio-api`. Give the Cloud Run runtime service account permission to send FCM messages (`cloudmessaging.messages.create`, using the appropriate Firebase Cloud Messaging role). Use Cloud Run's application default identity; do not download a service-account key.
4. Rebuild/install the app, sign in, open Stocks → bell, and enable phone notifications. Settings → Notifications opens the system controls. A notification tap opens the authenticated inbox, after unlocking if necessary.

### iPhone — requires paid Apple Developer membership

Until membership is available, keep `GABO_PUSH_ENABLED=NO`. Ordinary iPhone builds and in-app alerts still work.

Once enrolled, enable Push Notifications for bundle `dev.orestegabo.portfolio.Portfolio`, obtain a profile containing that entitlement, and create an APNs signing key. Store its private `.p8` content only in the API's secret store as `GABO_PUSH_APPLE_KEY`; configure `GABO_PUSH_APPLE_KEY_ID`, `GABO_PUSH_APPLE_TEAM`, and `GABO_PUSH_APPLE_TOPIC`. Do not commit the key.

Build with `GABO_PUSH_ENABLED=YES`. For development signing use `GABO_APNS_ENVIRONMENT=development` and `GABO_PUSH_ENVIRONMENT=sandbox`; for TestFlight/App Store use `production` for both. Push.entitlements includes associated domains and requires a suitable paid profile. Never select production merely because the build configuration is Release; it must match the provisioning profile.

## Privacy and delivery

Push bodies contain no prices, amounts, holdings or account identity. Details are fetched from the authenticated API. Permission revocation unregisters the device on the next app sync. Explicit logout unregisters that device before revoking the session. Signing another account into the same device reassigns the device token. Account deletion removes alerts, devices and queued deliveries.

Push is best-effort. Delivery records track attempts, expire stale alerts after three days for delivery purposes, and cap retries. Successful deliveries are not intentionally resent, but a crash after provider acceptance and before acknowledgement can result in a repeat; APNs collapse IDs and Android notification tags use the alert ID. Retries run on subsequent daily collections. Provider delivery has not been tested on a configured device yet.

Validate Android and iPhone notifications on real devices after configuration, including denied permission, account switching, logout, duplicate collection and tapping a push while the app is locked. Membership, provider configuration and deployment are still required before claiming phone push is operational.

## Missed price collection recovery — 26 November 2026

Production request logs showed the 25 November daily collection returning HTTP 503. Quote reads could subsequently save that day's closing observation, but only the daily collector created price alerts. The existing 15-minute bond-reminder dispatcher delivered queued alerts without collecting price changes. Decreases were already supported; this gap could suppress either direction after a failed daily collection.

The existing authenticated dispatcher now also uses the cached equity quote reader, records usable dated closes, and creates missing alerts from saved observations within today and the preceding two calendar days (Africa/Kigali). The 15-minute upstream cache remains in place. No new scheduler, provider, table or mobile API is required. First observations, unchanged prices, opted-out accounts and ineligible holdings retain their existing exclusions. Account/company/date uniqueness prevents repeat alerts and push queue duplication. Already-saved closes can recover even during an upstream outage. Database failures still fail the request after bond reminders and queued push retries have had their normal opportunity to run.

Verification used a new disposable PostgreSQL 18 cluster on loopback port 55469, with synthetic records only:

- The new dispatcher recovery regression failed before the fix (zero alerts instead of the expected rise and fall).
- The corrected dispatcher recovers 720 → 721 and 721 → 720, queues each once across repeated runs, and remains protected by scheduler authorization.
- A synthetic dated upstream verifies collecting a new decrease, cache reuse, and price-notification opt-out.
- `JAVA_HOME=<JDK 21> STRUCT_TEST_LOCAL_POSTGRES=true STRUCT_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55469/struct_test_price_recovery ./gradlew build --console=plain`: passed; 124 tests, zero failures/errors/skips. No separate static-analysis plugin is configured in this backend.

Device push display is not established by these synthetic tests; provider acceptance and phone delivery are separate from creation of the in-app alert.

### Production verification

The tested backend was activated before committing as revision `gabo-portfolio-api-price-recovery-1126-v2`, using image `europe-west1-docker.pkg.dev/gabo-portfolio-api/portfolio/api@sha256:b3608b76502867df34baa88679b4f2cff4b96a24dc4f3d55ee604c94c250128a`. The image packages the final locally verified JAR with the existing runtime settings. Candidate health and public health were `UP`; environment/secret references, resources, runtime identity, concurrency and timeout were preserved. No migration or scheduler configuration change was made.

An authenticated live dispatcher invocation returned `processed: true`, price collection `saved`, and **3** recovered alerts across eligible accounts. A second invocation returned **0** created alerts, confirming live deduplication. Provider/phone display was not manually verified. The unused first candidate revision was removed; previous production revisions were retained. The mobile checkout remained unchanged. Production activation and verification preceded the source commit.

## Mark all as read — 26 November 2026

`POST /api/bonds/notifications/read-all` requires the current authenticated account and the existing request-origin checks. It atomically updates that account's unread rows and returns `saved`, `markedRead`, and the remaining enabled-category `unreadCount`. It includes older pages and is independent of the existing per-ID read limit. Previously read timestamps and notification payloads remain unchanged. Repeating the request is harmless; subsequent new events remain unread. The existing single-item endpoint is unchanged. No migration is needed.

A disposable PostgreSQL 18 test run at loopback port 55469 passed `JAVA_HOME=<JDK21> STRUCT_TEST_LOCAL_POSTGRES=true STRUCT_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55469/struct_test_price_recovery ./gradlew build --console=plain`: 125 tests, zero failures/errors/skips (`/tmp/inzira-read-all-backend-checks.log`). The new integration test uses 250 synthetic events across old dates and another account, verifies authentication/isolation, preserves earlier read timestamps, checks idempotency and leaves subsequently inserted events unread.

The tested JAR was packaged with the existing runtime image/settings and activated as `gabo-portfolio-api-mark-all-read-1126`, image `europe-west1-docker.pkg.dev/gabo-portfolio-api/portfolio/api@sha256:494caaf6fdabd0785de04670caf1b665f17a9e2bdb6f8fef38febb6c37b38a4c`. Candidate and public health were UP; environment/secret references, resources, runtime identity, timeout and concurrency were preserved. A valid-origin unauthenticated POST returned 401; an origin-less request returned 403. No authenticated live bulk-read request was made and no real notifications were marked read for verification. Activation and preview verification preceded the source commit.
