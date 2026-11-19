package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Retire only shared blank PDFs, never investor purchases or their confirmation evidence. */
@Service
class BondApplicationFormCleanup(private val db: JdbcTemplate) {
    @Transactional
    fun deleteExpired(now: Instant = Instant.now()): Int {
        // Fence inserts/updates as well as existing rows: an unlinked pending application
        // must not appear between the absence check and deleting its issuance's form.
        // This short daily transaction takes no account locks and leaves reads available.
        db.execute("LOCK TABLE bond_purchases IN SHARE ROW EXCLUSIVE MODE")
        val deleted = db.query("""
            DELETE FROM bond_application_forms f
            WHERE f.metadata->>'category' = 'Bond application'
              AND f.metadata->>'visibility' = 'shared'
              AND f.metadata #>> '{applicationForm,issuanceDate}' < ?
              AND EXISTS (
                SELECT 1 FROM bond_issuance_documents r
                WHERE r.metadata->>'category' = 'Bond issuance'
                  AND r.created_at <= ?::timestamptz
                  AND r.metadata #>> '{issuance,settlementDate}' = f.metadata #>> '{applicationForm,settlementDate}'
                  AND r.metadata #>> '{issuance,maturityDate}' = f.metadata #>> '{applicationForm,maturityDate}'
                  AND r.metadata #> '{issuance,tenorYears}' = f.metadata #> '{applicationForm,tenorYears}'
              )
              AND NOT EXISTS (
                SELECT 1 FROM bond_purchases p
                WHERE p.status = 'submitted' AND p.linked_purchase_id IS NULL
                  AND (p.application_form_id = f.id OR (
                    p.issuer = 'Government of Rwanda' AND p.currency = 'RWF' AND p.market = 'primary'
                    AND p.settlement_date::text = f.metadata #>> '{applicationForm,settlementDate}'
                    AND to_jsonb(p.tenor_years) = f.metadata #> '{applicationForm,tenorYears}'
                  ))
              )
            RETURNING f.id::text
        """.trimIndent(), { row, _ -> row.getString(1) }, now.atZone(ZoneId.of("Africa/Kigali")).toLocalDate().toString(), now.minus(Duration.ofDays(30)).toString())
        for (id in deleted) {
            // Keep the historical notification and calendar destination, without a dead PDF link.
            db.update("UPDATE notifications SET payload=payload-'documentId' WHERE kind='subscriptions' AND payload->>'documentId'=?", id)
        }
        return deleted.size
    }
}
