# Price notifications

The Stocks navigation item shows the server unread count. Its app-bar bell opens a paginated inbox. Opening an alert expands its detail and marks that alert read only after the server acknowledges it. Financial text uses the existing number-hiding and capture-redaction components. English and French share the same implementation.

Spring creates alerts after the scheduled RSE collection, for active RWF/RSE holdings owned by each account on the market date. The comparison uses the preceding recorded close, which may be earlier than yesterday. The first observation and unchanged prices create no alerts. Repeated collections create no additional alert for the same account/company/market date. Holdings and cash cost are captured at that time, so later purchases do not rewrite historical impact. Results are estimates, not realised profit or sale proceeds.

## Activate after committing

Nothing is deployed by this change. Push the API commit first; its existing deployment pipeline applies V5 automatically. Then build/install the mobile app. Older APIs do not have the notification endpoints; the inbox explains this without blocking the portfolio.

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
