package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.temporal.ChronoUnit

/** Public terms only: never investor principal, account details, fees or statement descriptions. */
@Service
class BondInstrumentService(private val db: JdbcTemplate,private val json: JsonCodec,private val notifications: PriceNotifications) {
    fun instruments(): List<Record> = db.query("SELECT id,terms FROM bond_instruments ORDER BY settlement_date DESC,id",{r,_->json.read(r.getString(2))+mapOf("id" to r.getString(1))})
    private fun security(value: String)=normalized(value.uppercase().removeSuffix("/MN"))
    fun reference(p: Record): String? = instruments().singleOrNull {
        security(it.text("isin"))==security(p.text("isin")) && it.text("settlementDate")==p.text("settlementDate")
    }?.text("id")
    fun publish(id: String,terms: Record) {
        db.update("INSERT INTO bond_instruments(id,isin,settlement_date,terms) VALUES (?,?,?::date,?::jsonb) ON CONFLICT(isin,settlement_date) DO NOTHING",
            "issuance-$id",terms["isin"],terms["settlementDate"],json.write(terms))
        val saved=instruments().single { security(it.text("isin"))==security(terms.text("isin")) && it.text("settlementDate")==terms.text("settlementDate") }
        checkApi(listOf("couponRate","pricePercent","tenorYears").all { saved.decimal(it).compareTo(terms.decimal(it))==0 } &&
            listOf("maturityDate","firstCouponDate").all { saved.text(it)==terms.text(it) } && saved.strings("couponDates")==terms.strings("couponDates"),
            "Saved public bond terms disagree with this report. Review the existing instrument before publishing.",409)
        // Link source evidence to confirmed historical holdings; never rewrite their financial terms.
        val linked=db.queryForList("""UPDATE bond_purchases SET instrument_id=?,issuance_document_id=?::uuid,updated_at=now()
            WHERE status IN ('active','matured','sold') AND currency='RWF' AND issuer='Government of Rwanda' AND market='primary'
                AND upper(regexp_replace(isin,'/MN$','','i'))=upper(regexp_replace(?,'/MN$','','i'))
                AND settlement_date=?::date AND maturity_date=?::date AND issuance_document_id IS NULL
            RETURNING id::text,owner_id""",saved["id"],id,terms["isin"],terms["settlementDate"],terms["maturityDate"])
        for(row in linked) notifications.event(row.text("owner_id"),"documents","issuance:$id:${row["id"]}",notificationToday(),
            mapOf("stage" to "issuance","purchaseId" to row["id"],"documentId" to id,"title" to terms["bondName"],"action" to "document"))

    }
    /** Called only after an owned personal confirmation PDF has been checked by PortfolioService. */
    fun contributePrivateCouponTerms(p: Record) {
        if(p.text("status") !in listOf("active","matured","sold"))return
        val projected=PublicCouponTerms.project(p) ?: return
        db.update("INSERT INTO bond_security_coupon_terms(isin,maturity_date,terms) VALUES (?,?::date,?::jsonb) ON CONFLICT DO NOTHING",
            projected["isin"],projected["maturityDate"],json.write(projected))
        val stored=db.queryForList("SELECT terms,conflicted FROM bond_security_coupon_terms WHERE isin=? AND maturity_date=?::date FOR UPDATE",
            projected["isin"],projected["maturityDate"]).single()
        val old=json.read(stored["terms"].toString())
        if(!PublicCouponTerms.compatible(old,projected)) {
            // Keep the private transaction, but do not choose between conflicting contributors.
            db.update("UPDATE bond_security_coupon_terms SET conflicted=true WHERE isin=? AND maturity_date=?::date",projected["isin"],projected["maturityDate"])
        } else if(stored["conflicted"]!=true) {
            val merged=old+mapOf("couponDates" to (old.strings("couponDates")+projected.strings("couponDates")).distinct().sorted())
            db.update("UPDATE bond_security_coupon_terms SET terms=?::jsonb WHERE isin=? AND maturity_date=?::date",json.write(merged),projected["isin"],projected["maturityDate"])
        }
    }
    fun sharedCouponTerms(): List<Record> {
        val published=instruments().mapNotNull { PublicCouponTerms.project(it)?.plus("source" to "published") }
        val extracted=db.query("SELECT terms FROM bond_security_coupon_terms WHERE NOT conflicted",{r,_->json.read(r.getString(1))})
            .mapNotNull { PublicCouponTerms.project(it+mapOf("scheduleConfidence" to "confirmed")) }
        return (published+extracted).groupBy { it.text("isin") to it.text("maturityDate") }.values.mapNotNull { candidates ->
            val official=candidates.filter { it.text("source")=="published" }
            val chosen=official.ifEmpty { candidates }
            val first=chosen.first()
            if(chosen.any { !PublicCouponTerms.compatible(first,it) })null
            else first+mapOf("couponDates" to chosen.flatMap { it.strings("couponDates") }.distinct().sorted())
        }
    }
    fun options(item: Record,available: List<Record> = instruments()): List<Record> = if(item.text("kind")!="bond") emptyList() else available.filter {
        val settlement=date(it.text("settlementDate"));val posting=date(item.text("date"))
        settlement!=null && posting!=null && ChronoUnit.DAYS.between(settlement,posting) in 0..7 &&
            (item.text("isin").isBlank() || security(item.text("isin"))==security(it.text("isin")))
    }
}
