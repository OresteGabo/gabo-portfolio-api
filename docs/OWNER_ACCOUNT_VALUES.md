# Owner-only account investment values

Implemented 2026-11-20.

The existing owner-gated `GET /api/bonds/auth/accounts` now includes `portfolio.valuation`: per-currency `estimatedNetWorth`, `bondValue`, `shareValue`, and `unpricedShares`, plus `priceDate`, `stale` and `includesPending`. Existing account/count/activity fields remain compatible. No additional raw private lots, document data or credentials are returned. Anonymous/non-owner calls remain rejected.

OwnerAccountPortfolios owns account listing and valuation orchestration, using normal portfolio reads (including existing linked-pending exclusions), pending share orders and one shared market quote read. PortfolioService no longer builds account administration responses. No schema migration is needed.

The pure accountPortfolioValues function mirrors the mobile dashboard's valuation basis: principal for active/submitted bonds; remaining active shares acquired by the valuation date; pending share requests; positive RWF closing prices with a valid non-future publication date; remaining recorded cash cost where no applicable quote exists. Separate currencies remain separate. Closed/future confirmed holdings are excluded, and no new interest/cash/liability or FX assumptions are introduced. Exact BigDecimal sums/products are returned without a new rounding convention. Market failure produces explicit unpriced/cost fallback metadata.

## Verification

`JAVA_HOME=/Users/muhirwagabooreste/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home JAVA_TOOL_OPTIONS='-Dstruct.test.local-postgres=true -Dstruct.test.database-url=jdbc:postgresql://127.0.0.1:55491/struct_test_bond_linking' GABO_DATABASE_URL='jdbc:postgresql://127.0.0.1:55491/struct_test_bond_linking' GABO_DATABASE_USER=struct_test GABO_DATABASE_PASSWORD= ./gradlew test bootJar --console=plain` passed: 119 tests, zero failures/errors/skips, using only disposable local test data. New tests characterize pending/partial-sale values, precision, currency separation, unavailable prices, empty accounts and owner-only HTTP access/account isolation. Spring startup resolves the new service dependencies. No production credentials or data were loaded for tests.

No live deployment is implied by these tests. Activation is subject to explicit approval and must preserve existing runtime configuration.

## Approved activation — 2026-11-20

Activated after explicit user approval, without committing or pushing. Exact tested JAR SHA-256: `de43f991676252932bf86d3af85cc89ee7c80d7c8ee59363b1adec665c96c348`; the same hash was verified inside the non-root linux/amd64 Java 21 container. Image digest: `sha256:9f5a6baf5a05d5af0bfdd514f4e6af2e02395dca88946df718be5880508d4645`.

Revision `gabo-portfolio-api-owner-values-de43f9916762` was deployed first with no traffic. Existing runtime, service identity, environment and secret references were compared and preserved. Candidate health was UP and anonymous Accounts access returned 401 with a valid native origin. Production was promoted to that revision at 100%; public health stayed UP. The temporary candidate tag was removed, existing tags preserved. Previous revision: `gabo-portfolio-api-gh-44635372334-1` (source `dc2e1f6`). Current labels explicitly mark this as an uncommitted build on that base. No migration or actual investment/account-permission mutation was performed; no real portfolio values were fetched for rollout checks. Private configuration snapshots were deleted after comparison. Synthetic tests, rather than a real user session, verified authenticated owner and non-owner response behavior.

## Commit verification — 2026-11-20

Re-executed the complete test and bootJar tasks with `--rerun-tasks` against the disposable local PostgreSQL configuration above: 119 tests passed, zero failures/errors/skips; JAR build passed. `git diff --check` passed. Only feature code, its tests and this report are included in the backend commit. The normal main-branch CI/deployment workflow runs after push; no separate manual rollout is needed.
