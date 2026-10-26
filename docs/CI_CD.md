# Automatic API deployment

Push to `main` in https://github.com/OresteGabo/gabo-portfolio-api to build, test and deploy. Pull requests run the build and PostgreSQL integration tests without production access. The Actions tab also offers Run workflow for `main`.

Workflow: `.github/workflows/deploy.yml`. Track runs at https://github.com/OresteGabo/gabo-portfolio-api/actions/workflows/deploy.yml.

## Rollout

1. Java 21 / Gradle build and PostgreSQL 18 integration tests must pass.
2. Build the tested commit into a Linux amd64 image, publish it with its commit SHA, and deploy its immutable digest.
3. Start a candidate Cloud Run revision with no production traffic. Existing secrets, environment, runtime identity and scaling settings are preserved.
4. Verify API/database health before sending production traffic to the revision.
5. Check the public health endpoint again. Restore previous revision traffic on promotion/public verification failure. A failed candidate is never promoted.

Production rollouts are serialized. Actions use pinned commits. No Google service-account keys, owner passwords or database credentials are stored in GitHub. GitHub receives short-lived identity through Workload Identity Federation. The provider restricts access to this repository's numeric ID, owner ID, main branch and this exact workflow. The dedicated deployer can publish to the Portfolio image repository, update only the Portfolio Cloud Run service and use its runtime identity; the runtime identity retains its existing Secret Manager access.

Repository variables (non-secret): `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_DEPLOY_SERVICE_ACCOUNT`. Both are configured. Google project `gabo-portfolio-api`, region `europe-west1`, pool `github-portfolio`, provider `github-main`, deployer `portfolio-deployer@gabo-portfolio-api.iam.gserviceaccount.com`.

Flyway migrations run during application startup. Keep migrations compatible with the previous revision during rolling deployment. Restoring traffic does not undo database migrations; never edit an already applied migration. Add a new version instead.

The website repository `OresteGabo/gabo-cv` already deploys automatically through Vercel. API changes need only an API push: its stable URL means no mobile rebuild or website deployment is needed for compatible backend changes. Native UI changes still require a new phone build.
