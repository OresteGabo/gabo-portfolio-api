package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Service
class StatementService(private val db: JdbcTemplate,private val json: JsonCodec,private val portfolio: PortfolioService) {
    private fun validate(raw: Record): Record {
        val error="Could not verify this BK Capital statement."
        checkApi(raw.text("kind") in listOf("bond","equity") && date(raw.text("date"))!=null && raw.text("currency")=="RWF" && raw.text("broker")=="BK Capital" && raw["amount"] is Number && raw.number("amount")>0 && raw.number("amount")<=1e13 && raw.text("title").isNotBlank() && raw.text("title").length<=160,error)
        val p=raw.filterKeys { it in listOf("kind","date","amount","title","currency","broker") }.toMutableMap()
        if(raw.text("kind")=="equity") {
            checkApi(Regex("[A-Z][A-Z0-9]{1,9}").matches(raw.text("ticker")) && Regex("\\d{1,20}#\\d{1,8}").matches(raw.text("contractNote")) && raw["shares"] is Number && raw.number("shares")>0 && raw.number("shares")<=1e13 && raw.number("shares")%1==0.0 && raw["pricePerShare"] is Number && raw.number("pricePerShare")>0 && raw.number("pricePerShare")<=1e13 && raw.number("amount")+.02>=raw.number("shares")*raw.number("pricePerShare"),error)
            for(k in listOf("ticker","contractNote","shares","pricePerShare")) p[k]=raw[k]
        }
        return p
    }
    fun matches(item: Record,p: Record): Boolean {
        if(p.text("currency")!=item.text("currency") || p.text("status")=="submitted") return false
        if(item.text("kind")=="equity") return p.text("instrumentType")=="equity" && normalized(p.text("broker"))=="BKCAPITAL" && listOf("contractNote","ticker").all { p[it]==item[it] } && p.text("tradeDate")==item.text("date") && p.number("shares")==item.number("shares") && abs(p.number("pricePerShare")-item.number("pricePerShare"))<=.02 && abs(p.number("netAmountPayable")-item.number("amount"))<=.02
        if(p.text("instrumentType")=="equity") return false
        val days=ChronoUnit.DAYS.between(date(p.text("settlementDate")) ?: return false,date(item.text("date")) ?: return false)
        return days in 0..7 && abs(p.number("faceValue")*p.number("pricePercent")/100+p.number("accruedInterestPaid")-item.number("amount"))<=.02
    }
    fun missing(owner: String): List<Record> {
        val purchases=portfolio.bonds(owner)+portfolio.shares(owner)
        return db.query("SELECT id::text,item FROM bond_statement_items WHERE owner_id=? ORDER BY created_at DESC LIMIT 500",{r,_->json.read(r.getString(2))+mapOf("id" to r.getString(1))},owner).filter { item->purchases.count { matches(item,it) }!=1 }
    }
    @Transactional fun reconcile(owner: String,raw: Record): List<Record> {
        lockAccount(db,owner)
        val input=raw["items"] as? List<*> ?: throw ApiError(400,"Could not verify this BK Capital statement.")
        checkApi(input.size in 1..500,"Could not verify this BK Capital statement.")
        val items=input.map { validate(it.record()) }; val purchases=portfolio.bonds(owner)+portfolio.shares(owner)
        return items.map { item->
            val recorded=purchases.count { matches(item,it) }==1
            if(!recorded) {
                val identity=Passwords.sha(json.write(listOf(item["kind"],item["date"],item["amount"],item["ticker"] ?: "",item["contractNote"] ?: "",item["shares"] ?: 0,item["pricePerShare"] ?: 0)))
                db.update("INSERT INTO bond_statement_items(owner_id,identity,item) VALUES (?,?,?::jsonb) ON CONFLICT(owner_id,identity) DO NOTHING",owner,identity,json.write(item))
            }
            item+mapOf("state" to if(recorded) "recorded" else "missing")
        }
    }
}
