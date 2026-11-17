# Investment transactions, cash, activity and portable backups

## Using the features

- **Settings → Investing → Cash & transactions** opens the cash ledger. A share's detail screen also has **Record sale or dividend** and preselects a purchase.
- Record deposits (including opening funding), withdrawals, net dividends received, or a sale of whole shares from a selected purchase. Review the calculated proceeds and profit before saving. The transaction can be removed after confirmation to correct an entry.
- Expand **Investment activity** in bond or share details for the linked purchase, documents, coupon receipts and share transactions. Automatic coupon markings are explicitly identified.
- Share details show the market date, RSE source and whether the quote is cached. Bond details distinguish confirmed and estimated coupon schedules.
- In personal **Guest Mode**, **Settings → Investing → Encrypted backup** exports or restores investments, transactions, coupon receipts and original PDFs. Keep the backup password safely: it cannot be recovered. Account portfolios continue to use server storage; this portable restore replaces only a guest workspace.

## Financial boundaries

Sales currently cover shares, including partial sales, not bond sales/redemptions. Original purchase quantities, costs and PDFs stay saved. Remaining shares and acquisition cost are derived from the sale ledger. Purchase cost is allocated proportionally within the selected purchase, rounded HALF_UP to two decimal places. Allocation is capped at the remaining cost; the final sale consumes the rounding remainder. This is investment recordkeeping, not tax-lot advice or a tax report.

Realized gain is net sale proceeds after the entered fees/taxes minus allocated acquisition cost. Dividends are the net cash actually received, entered manually; no upcoming dividend is inferred. There is no broker connection.

Recorded cash is deposits + dividends + net sale proceeds + manually confirmed coupons − withdrawals − saved acquisition costs. Automatic coupon markings are excluded. Missing opening funding or older transactions can make the recorded balance negative; it is not a verified broker balance or a recommendation to invest that amount. Currencies stay separate. Investment worth still excludes cash to avoid silently changing its meaning; cash/income appears separately in reports. Annual share positions apply only sales through the report cutoff.

## Storage and permissions

The API's additive `V14__investment_cash_ledger.sql` stores one small record per actual transaction, scoped to the authenticated account. Client UUIDs make retries idempotent. Account locks serialize sales and prevent concurrent overselling; authoritative proceeds and gains are calculated by the server. Remaining holdings and price-impact alerts use the sale-adjusted position. No daily portfolio snapshots are introduced.

`V15__guest_cash_ledger_transfer.sql` extends the explicit, consent-based guest migration with cash transactions. Linked share purchases transfer first; cash retries reuse acknowledgments. No local original is deleted. Guest schema version 4 prevents older clients from silently ignoring sale history, and versions 1–3 migrate without rewriting purchase facts. Purchases with linked guest transactions cannot be edited/deleted until those transactions are removed.

## Backup format and failure safety

A `.scbackup` file contains a versioned JSON workspace and deduplicated original PDF bytes inside an authenticated encryption envelope. `SCBK1` uses a fresh 16-byte salt and 16-byte IV, PBKDF2-HMAC-SHA256 (600,000 iterations, 64 output bytes), AES-256-CBC with PKCS#7 padding, and HMAC-SHA256 over the header and ciphertext using a separate key. The MAC is compared before decryption. iOS uses CommonCrypto/Security; Android uses JCA, with the RFC 8018 HMAC-based PBKDF2 path for Android 7 providers lacking the SHA-256 factory alias. Passwords are not stored.

The limit is 48 MB per archive, 100 PDFs, and the existing 4 MB workspace limit. Files are selected/saved through native document pickers. Backups can move between iPhone and Android; there is no automatic upload or recurring backup.

Restore checks the password/MAC, format, schema, identifiers, document hashes/envelopes, references and transaction rules before replacing the workspace. New PDFs are staged under fresh IDs. The prior manifest tracks staged files for crash cleanup; the restored manifest is committed only after all new files are saved. Previous PDFs are removed after that commit. Bad passwords, damaged archives and failed staging retain the current investments. Restore requires an explicit replacement confirmation.

## Rollout

Deploy the API migrations/code before distributing the app. The app disables account transaction editing when the endpoint is unavailable; existing investments remain usable. This implementation does not itself deploy production or install the app on a physical phone. The pending device-detail migration V13 remains separate from the ledger migrations.

## Validation (8 November 2026)

- Core financial tests: 70 passed on JVM and 70 on the iPhone simulator.
- Shared app tests: 376 passed on Android host and 399 on the iPhone simulator.
- API PostgreSQL integration/unit tests: 81 passed, including retry deduplication, account isolation, concurrent overselling, full-sale alert suppression and guest-sale transfer.
- Android native transaction UI: the offline sale-review/save/correction journey passed on API 36.1. Synthetic screenshots were inspected.
- Android debug app/test APK builds and iOS ARM64 compilation passed. Android release lint reported zero errors.
- Backup tests cover an independent OpenSSL/Hashlib envelope, Android/iOS interoperability, randomized encryption, wrong passwords, MAC tampering, PDFs, staged-file failure and repeat restore cleanup. Android 7's KDF compatibility path matches the standard provider, including a Unicode password.

No production data was modified during these checks. Physical-device acceptance testing and production rollout remain separate.
