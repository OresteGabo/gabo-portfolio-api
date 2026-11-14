# Investment website domain — 14 November 2026

The investment frontend is being migrated to `https://invest.orestegabo.dev`. Its source is independent of the CV, in `WebstormProjects/gabo-portfolio`. The old `https://bonds.orestegabo.dev` remains an allowed origin for existing mobile/web clients during migration.

## Backend rollout completed

Cloud Run service `gabo-portfolio-api` in project `gabo-portfolio-api`, region `europe-west1`, now routes 100% of production traffic to revision `gabo-portfolio-api-00040-pof`. Only the allowed-origin environment setting changed; the existing image, database, secrets, scaling settings, and existing Firebase setup tag were retained.

`GABO_ALLOWED_ORIGINS` contains exactly:

- `https://bonds.orestegabo.dev`
- `https://gabo-portfolio-api-639858675564.europe-west1.run.app`
- `https://invest.orestegabo.dev`

The ignored `.env.cloudrun` also retains the new origin for future environment preparation. Do not replace production origins with the localhost defaults in example configuration.

The candidate was first deployed with no production traffic, then checked at `/actuator/health` and with CORS OPTIONS requests for login from both website origins. After promotion, the same checks passed against the public API URL. No account or investment data was changed for verification.

## Frontend and client requirements

The website still proxies `/api/bonds/*` to the same backend; no API endpoint rename is needed. Vercel project `gabo-portfolio` owns the new frontend deployment. DNS and the encrypted cron credential must be configured there before completing the frontend cutover. The exact website deployment and domain status are documented in the frontend repository's `BONDS_DEPLOYMENT.md`.

The website's domain rename does not change the mobile app binary or its configured server. Keep the old origin allowed until existing clients have migrated. Cookies and browser localStorage do not automatically transfer between subdomains; users can sign in to the same account at the new website once it is live. Keep exact origin matching; do not widen the cookie domain or add wildcard CORS to make the migration work.
