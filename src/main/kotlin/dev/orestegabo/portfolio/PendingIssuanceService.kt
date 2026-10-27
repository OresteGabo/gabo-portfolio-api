package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class PendingIssuanceService(private val db: JdbcTemplate,private val json: JsonCodec) {
    private fun issues()=db.queryForList("SELECT id::text,metadata FROM bond_issuance_documents").map { it+mapOf("terms" to json.read(it["metadata"].toString())["issuance"].record()) }
    private fun matching(p: Record,issues: List<Record>): Record? {
        if(p.text("status")!="submitted" || p.text("currency")!="RWF" || p.text("issuer")!="Government of Rwanda" || p.text("market")!="primary")return null
        val candidates=issues.filter { row -> val issue=row["terms"].record()
            issue.text("settlementDate")==p.text("settlementDate") &&
                if(p.text("isin").isNotBlank())issue.text("isin").uppercase().removeSuffix("/MN")==p.text("isin").uppercase().removeSuffix("/MN") else issue.number("tenorYears")==p.number("tenorYears") }
        val exact=candidates.filter { it["terms"].record().text("maturityDate")==p.text("maturityDate") }
        return exact.singleOrNull() ?: candidates.singleOrNull()
    }
    // Application identity and investor cash/quantity are unchanged. These are public terms, not allocation evidence.
    fun refresh(owner: String?=null): Int {
        val issues=issues();if(issues.isEmpty())return 0
        val rows=if(owner==null)db.queryForList("SELECT * FROM bond_purchases WHERE status='submitted'") else db.queryForList("SELECT * FROM bond_purchases WHERE status='submitted' AND owner_id=?",owner)
        var changed=0
        for(row in rows) {
            val input=mapOf("status" to row["status"],"issuer" to row["issuer"],"currency" to row["currency"].toString().trim(),"market" to row["market"],
                "isin" to row["isin"],"tenorYears" to row["tenor_years"],"settlementDate" to row["settlement_date"].toString(),"maturityDate" to row["maturity_date"].toString())
            val match=matching(input,issues) ?: continue
            val terms=match["terms"].record()
            if(row["issuance_document_id"]?.toString()==match.text("id") &&
                (row["coupon_rate"] as Number).toDouble()==terms.number("couponRate") &&
                json.mapper.readValue(row["coupon_dates"].toString(),List::class.java)==terms.strings("couponDates") &&
                row["maturity_date"].toString()==terms.text("maturityDate") && row["first_coupon_date"].toString()==terms.text("firstCouponDate") &&
                (row["coupon_frequency"] as Number).toInt()==terms.number("couponFrequency").toInt() && row["schedule_confidence"]==terms["scheduleConfidence"])continue
            changed+=db.update("""UPDATE bond_purchases SET coupon_rate=?,first_coupon_date=?::date,coupon_dates=?::jsonb,
                coupon_frequency=?,schedule_confidence=?,maturity_date=?::date,issuance_document_id=?::uuid,updated_at=now()
                WHERE id=?::uuid AND status='submitted'""",terms["couponRate"],terms["firstCouponDate"],json.write(terms["couponDates"]),
                terms["couponFrequency"],terms["scheduleConfidence"],terms["maturityDate"],match["id"],row["id"].toString())
        }
        return changed
    }
    fun decorate(p: Record,document: Any?,method: Any?,personal: Any?,terms: Record): Record {
        val ready=terms.isNotEmpty() && p.text("status")=="submitted"
        val cash=if(ready) p.decimal("faceValue")*terms.decimal("pricePercent")/BigDecimal(100)+p.decimal("accruedInterestPaid")+p.decimal("feesPaid") else BigDecimal.ZERO
        val preview=if(ready)p+terms.filterKeys { it in listOf("bondName","isin","couponRate","couponDates","couponFrequency","firstCouponDate","scheduleConfidence","maturityDate","pricePercent") }+
            mapOf("notes" to if(p.text("notes")=="Pending application. Allocation, executed price and coupon are unconfirmed; stored price is provisional.") "" else p.text("notes"),
                "purchaseDate" to minOf(p.text("purchaseDate"),p.text("settlementDate")),"amountInvested" to cash.setScale(2,RoundingMode.HALF_UP),"status" to "active") else null
        return p+mapOf("issuanceDocumentId" to document?.toString(),"confirmationMethod" to method,"confirmationDocumentId" to personal?.toString(),
            "publishedTermsAvailable" to ready,"canConfirmWithoutPdf" to (ready && (cash-p.decimal("amountInvested")).abs()<=BigDecimal("0.02")),"manualConfirmation" to preview)
    }
}
