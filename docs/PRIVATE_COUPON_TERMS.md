# Public coupon facts from private confirmations

1 November 2026. Original PDFs and investor records remain private. A saved owned bond confirmation can contribute a separate explicit public-security projection; manual entries/staged uploads alone do not contribute.

Flyway V10 adds `bond_security_coupon_terms` with security/maturity identity, an allowlisted JSON projection and a conflict flag. Acquisition dates cannot be used as a public auction identity, so existing auction-specific `bond_instruments` is not repurposed for these private-source facts. No source PDF, owner/account ID, principal, fees, personal tax or executed price is retained in this public projection.

`GET /api/bonds/coupon-terms` requires authentication and returns `{terms:[...]}`. Allowed fields: `isin`, `issuer`, `currency`, `instrumentType`, `maturityDate`, `couponRate`, `couponFrequency`, `tenorYears`, `couponDates`, `source`. Published instruments take precedence (`source=published`); private-document extractions are labelled `source=document-extracted`. Estimated dates are omitted and unknown zero rates do not publish. Incompatible rates/frequencies or non-nested date variants suppress the contribution instead of choosing a value or unioning extra coupons. Private saves still succeed without rewriting either investor's financial record. Published authority can supply the display even when contributions conflict.

The mobile client loads these facts without blocking portfolio navigation. Calendar identity hints require unique matching maturity/tenor when a public calendar lacks an ISIN; import enrichment requires exact security/maturity and fills missing fields only. A private document is never offered as a shared download or used as public allocation proof. Existing holdings are not silently recalculated or confirmed.

No private-history backfill or migration of investor figures is performed. Contribution occurs on future owned confirmation saves/re-attachments. The dataset can survive private record/account deletion because the projection contains no investor/source references. Independent extraction/authenticity verification and operator correction workflows remain future work; the current beta trust limitations still apply. The bilingual mobile notice discloses the projection.

## Restoration

Deleting a purchase intentionally retains its independent original PDF. Reusing that owned PDF can create a replacement purchase with a new ID. File duplicate detection does not suppress a missing financial write. Backend DELETE/recreate/repeat tests verify one retained PDF and one resulting holding; foreign users still cannot use the private document.

## Verification and rollout

62 backend tests and bootJar pass on a fresh disposable PostgreSQL cluster, including V10 migration, strict projection, ownership denial, conflict suppression, compatible partial schedules, published-source precedence and delete/recreate idempotency. Companion mobile tests and results are in `docs/VERIFICATION.md` in the KMP repository.

Deploy backend/Flyway V10 before the app. V10 is additive; rollback application code without dropping live tables or original records. No production deployment performed.
