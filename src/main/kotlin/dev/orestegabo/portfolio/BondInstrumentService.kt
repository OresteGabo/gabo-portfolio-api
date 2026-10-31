package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.temporal.ChronoUnit

/** Public terms only: never investor principal, account details, fees or statement descriptions. */
@Service
class BondInstrumentService(private val db: JdbcTemplate,private val json: JsonCodec) {
    fun instruments(): List<Record> = db.query("SELECT id,terms FROM bond_instruments ORDER BY settlement_date DESC,id",{r,_->json.read(r.getString(2))+mapOf("id" to r.getString(1))})
    fun find(id: String): Record? = instruments().firstOrNull { it.text("id")==id }
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
    }
    fun options(item: Record,available: List<Record> = instruments()): List<Record> = if(item.text("kind")!="bond") emptyList() else available.filter {
        val settlement=date(it.text("settlementDate"));val posting=date(item.text("date"))
        settlement!=null && posting!=null && ChronoUnit.DAYS.between(settlement,posting) in 0..7 &&
            (item.text("isin").isBlank() || security(item.text("isin"))==security(it.text("isin")))
    }
}
