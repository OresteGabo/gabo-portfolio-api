# Monthly and yearly investment goals

Implemented 2026-11-21. Migration V21 extends notification_preferences with goal_period (monthly/yearly) and yearly_target. Existing monthly_target and notification choices are preserved. Existing rows remain monthly and receive a bounded monthly × 12 yearly suggestion. Missing accounts retain monthly 100,000 / yearly 1,200,000 defaults. Old clients sending monthlyTarget alone leave yearlyTarget and goalPeriod unchanged. Invalid periods or nonpositive/fractional/out-of-range amounts are rejected.

The existing account-scoped notification-preferences endpoint exposes both amounts and active period. Its category and auto-coupon behavior is unchanged. Goal events use the current Rwanda calendar month or year, confirmed purchases plus pending bond/share requests including recorded fees, never market gains. Monthly event identities remain compatible; yearly milestones deduplicate by calendar year. The payload includes goalPeriod, year, confirmed, pending and amount; month remains present for older clients. The client identifies pending-inclusive milestones. This adds no separate goal-deadline notifications.

`goalContributionProgress` reuses MobileOverview monthly contribution totals and adds pending share orders once. It excludes foreign/future/cancelled requests and rolls over at the calendar-year boundary. No holdings or fee/coupon formulas are changed by the migration.

## Verification

`JAVA_HOME=/Users/muhirwagabooreste/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home JAVA_TOOL_OPTIONS='-Dstruct.test.local-postgres=true -Dstruct.test.database-url=jdbc:postgresql://127.0.0.1:55491/struct_test_bond_linking' GABO_DATABASE_URL='jdbc:postgresql://127.0.0.1:55491/struct_test_bond_linking' GABO_DATABASE_USER=struct_test GABO_DATABASE_PASSWORD= ./gradlew test bootJar --console=plain` — passed, 122 tests, zero failures/errors/skips. New cases cover old preference preservation, max-target bounded migration backfill, yearly/legacy writes, invalid periods/amounts, calendar selection/reset, pending inclusion and notification deduplication. Only disposable local data was used. `git diff --check` passed.

The client additionally passed 148 core tests, 591 shared tests, three Android UI tests, Android/iOS compilation and signed iPhone build/install. No live mutation or migration is implied by those checks. Activation requires explicit approval. A rollback to an older backend would resume monthly reminder semantics, so preserve a goal-aware revision after activating yearly mode.

## Approved production activation — 2026-11-21

Activated after explicit approval, without committing or pushing. The exact tested JAR SHA-256 `216664645058d3f20fe0c5b9321588a72f6553d5fd90a3fe5f0e69e1d19154a4` was verified inside a non-root linux/amd64 Java 21 container. Image digest: `sha256:277a5de797228c6db4f72e909bdcab9c11bc3d540cefdc403fcd629c37bae2cb`.

The no-traffic candidate `gabo-portfolio-api-goals-216664645058` preserved runtime settings, service identity, environment/secret references, template annotations and production traffic. Flyway validated 21 migrations and successfully applied V21. Candidate health was UP and anonymous goal-preference access returned 401 with a valid native origin. After promotion, that revision serves 100% of production traffic and public health is UP. Existing tags were preserved; the temporary candidate tag was removed. Previous revision: `gabo-portfolio-api-gh-44671327532-1` (source `c2399b0`). Labels explicitly mark the new image as uncommitted work based on that source.

No real account's goal was changed as a deployment test, and no investment records were retrieved or mutated for verification. Tests cover monthly preservation/backfill; migration logs and health confirm successful live application. The preceding 122 tests were not represented as re-executed during deployment. Docker had been stopped; it was started before packaging. Private temporary service configuration snapshots were removed after comparison.

## Commit verification — 2026-11-21

Re-executed all backend test and bootJar tasks with `--rerun-tasks` against the disposable loopback PostgreSQL configuration above: 122 tests passed, zero failures/errors/skips. `git diff --check` passed. The normal main-branch CI/deployment workflow runs after the approved push; no separate manual rollout is part of this Git operation. Only goal-related implementation, migration, tests and this report are included.
