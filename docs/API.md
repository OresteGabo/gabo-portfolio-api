# Existing client contract

All paths below are prefixed with `/api/bonds`. JSON errors expose a short `error` message and an appropriate status, never SQL, stack traces or extracted document content.

| Method | Path | Access / response |
|---|---|---|
| POST | `/auth/login` | Public; `{email,password}` → `{authenticated,user}` + cookie |
| GET | `/auth/session` | Public; `{authenticated,user}` |
| POST | `/auth/logout` | Signed in; revokes session and clears cookie |
| POST | `/auth/register` | Public, valid single-use invite; `{email,password,invite}` |
| GET | `/auth/registration` | Owner; current invitation status |
| POST | `/auth/registration` | Owner; `{email}` (or blank) → 48-hour invitation |
| DELETE | `/auth/registration` | Owner; closes invitations |
| GET | `/auth/accounts` | Owner; account activity summaries |
| DELETE | `/auth/accounts/{id}` | Owner; email confirmation; invited accounts only |
| DELETE | `/auth/account` | Signed in; password confirmation; own invited account only |
| GET | `/purchases` | Signed in; complete `{purchases}` |
| POST | `/purchases` | Signed in; validated purchase → 201 `{purchase}` |
| GET | `/purchases/{id}` | Signed in; own `{purchase}` |
| PUT | `/purchases/{id}` | Signed in; update/confirm own purchase without a second transaction |
| DELETE | `/purchases/{id}` | Signed in; own purchase |
| GET | `/equities` | Signed in; complete `{holdings}` |
| POST | `/equities` | Signed in; validated contract fields → 201 `{holding}` |
| GET | `/mobile-overview` | Signed in; recent previews plus full active bond basis, older share aggregates and monthly contributions |
| GET | `/documents` | Signed in; own metadata/download URLs |
| POST | `/documents` | Signed in; chunked private PDF upload |
| GET | `/documents/{id}` | Signed in; own PDF bytes; preserved legacy owner IDs also accepted |
| GET | `/statement-items` | Signed in; unresolved `{items}` |
| POST | `/statement-items` | Signed in; `{items}` → reconciliation `{rows}` with recorded/missing states |
| POST | `/import-diagnostics` | Signed in; allowlisted codes only; 204 |
| GET | `/rse/market?refresh=1` | Public cached bond market/scores; authenticated callers may force refresh |
| GET | `/rse/quotes?kind=equities` | Public cached `bonds` or `equities`; authenticated callers may force `refresh=1` |
| GET | `/rse/equity-history?tickers=IMR,BOK&period=year` | Public official observations; authenticated callers additionally receive only their own account’s contract-price evidence; up to ten symbols and month/year/all |
| GET | `/rse/daily` | Scheduler bearer secret; fresh collection and bounded run status |
| GET | `/rse/snapshot` | Snapshot bearer secret; refresh bond data and return counts |

The complete history routes retain their existing unpaginated contract because exports and See all depend on it. The mobile overview limits previews to twelve per asset class from the previous year without dropping older holdings from totals or schedules.

## Purchase validation

Treasury principal must be positive and in multiples of RWF 100,000. Treasury withholding defaults to 5% when absent; validated supplied rates are retained. Dates must be real calendar dates; maturity follows purchase. Confirmed schedules require coupon dates. Cash cost is recalculated from principal × price / 100 + accrued interest + fees; a caller-supplied total is not authoritative. Pending status is `submitted` and is never silently upgraded from catalog terms.

Equity imports require RSE/RWF, an ISIN/ticker, positive integer shares, consistent trade/settlement dates and agreement among consideration, fee components, total charges and cash payable within RWF 0.02 (matching the existing contract). Duplicate financial identities, broker contract numbers and trade-report IDs are rejected per account with 409. Internal decimals preserve imported precision; rounding for display remains a client concern.

## Documents

POST JSON: `{uploadId,index,count,content,metadata}`. `uploadId` is 32 lowercase hexadecimal characters, content is canonical base64, count is 1–20, chunks are 512 KiB except the last, maximum complete PDF is 10 MiB. Metadata has category `Bond confirmation`, `Share contract`, `Statement` or `Bond issuance` (owner publication only), a real `documentDate`, and string `label`, `instrumentName`, `issuer` (up to 240 characters each).

Unfinished uploads are private, hidden from the library and limited to eight per account. Uploads older than one day are cleaned on a later upload by the same account; there is no independently scheduled purge. Completion validates a supported PDF header and end marker, in addition to the size/chunk limits. This is envelope validation, not malware or signature verification. Completed content is deduplicated by account and SHA-256. Responses contain `{}` while incomplete, then `{documentId}`. Resending a conflicting chunk or metadata is rejected.

Statement imports create missing-document reminders, not invented holdings or principal values. A later confirmed contract resolves the reminder when its financial fields match.

## Intentional differences from the Next implementation

- PostgreSQL is mandatory; no read-only serverless JSON owner fallback.
- Login is rate-limited as well as registration and account deletion.
- Dates are strictly validated (impossible dates such as February 30 are rejected).
- Multiple exact origins are configurable to support the independent API; mutations with absent/unapproved Origin are rejected.
- Scheduler endpoints require configured secrets even in development.
- Imported bundled PDFs move into the same authenticated document store; no web-directory access is required after migration.


## Security boundaries updated 30 October 2026

Only the existing owner account may publish a `Bond issuance` PDF/canonical terms shared across accounts. Invited users can still read published reports and privately upload personal confirmations, share contracts and statements. Unprivileged publication is rejected before staging. Existing shared data requires operator provenance review; this permission change does not validate old reports.

`/rse/equity-history` keeps its response shape. Anonymous callers receive official observations with no private contract rows and `tradeHistoryAvailable=false`; authenticated users may receive their own derived contract prices. No user-supplied owner query/body parameter selects another account.

Scheduled per-account receipt/reminder work now has an explicit transaction boundary, including calls from the daily job. No database migration was introduced. Full audit, dependency results, remaining operations and Rwanda legal-verification questions are in the companion mobile checkout's `docs/security/`. Backend deployment must precede or accompany the updated app/privacy notices.

## Reviewed statement bond purchases (31 October 2026)

Flyway V9 adds public `bond_instruments` (existing catalog/shared issuance terms, unique security + settlement) and optional purchase references `instrument_id` / private `statement_document_id`. Existing purchase fields, IDs, amounts and API contracts remain; old columns are retained as historical/compatibility snapshots. Publishing an owner-reviewed issuance registers its terms without silently replacing conflicting schedules. Purchase responses add `instrumentId` and `statementDocumentId`.

Statement rows add `bondOptions` only for unmatched, privately sourced bond entries with compatible saved terms. Authenticated `POST /api/bonds/statement-items/{id}/bond` accepts `instrumentId`, explicit numeric `faceValue`, `pricePercent`, `accruedInterestPaid`, `feesPaid`, `withholdingTaxRate`, `confirmed: true`, `reason` and `expectedDecisionAt`. It uses the same owner scope, lock, transaction, purchase validation/uniqueness and append-only statement decisions. It returns `{rows:[updatedStatementRecord]}`. Public terms are server-selected; acquisition date is the selected primary settlement date and original posting date is preserved in statement evidence.

No automatic creation from a month/amount. Require a saved owner-authorized original statement, compatible identity/date and execution cost, explicit personal figures and no existing/pending candidate. Missing/invalid inputs yield 400, inaccessible rows 404, inconsistent/duplicate/stale state 409. Equivalent retries return the existing purchase; changed figures do not mutate it. Cash/fee/coupon/dividend movements remain evidence only. The original statement is linked as a statement, not mislabeled a personal allocation confirmation.

Deploy V9/backend before using this action. Existing clients continue operating; old servers omit options so new clients retain their existing confirmation-upload path. Production migration/deployment is not claimed by source tests. Shared instrument terms contain no customer identities and survive deletion of an individual account; customer purchases, statement sources and decisions follow existing account deletion.

## Security notification destinations (31 October 2026)

Authenticated GET `/api/bonds/notifications/sign-in-activity` reads existing `account_devices` registrations for the current account only. Optional `notificationId` must identify an owned security notification; the server resolves its existing `device:<uuid>` event key, so historical notifications need no payload migration. Returns `{devices:[{deviceId,platform,firstSeen}],highlightDeviceId}` with at most 100 entries, putting the selected device first even after newer registrations. These records describe first-observed sign-ins/installations, not currently active sessions, hardware attestation or last-seen state.

Invalid notification IDs return 400, inaccessible/non-security alerts 404 and unauthenticated access 401. No caller-supplied owner, session token, IP, password or new device metadata is exposed. This is read-only; no schema change, new retention scheme, remote revocation or financial mutation. Integration regressions check exact older-alert targeting, allowlisted fields and cross-account denial. The mobile inbox now directs security events here, target events to the existing target editor and report events to existing report options; old `action=settings` payloads remain supported by category.
