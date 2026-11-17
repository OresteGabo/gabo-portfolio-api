package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class InvestmentLedger(private val db: JdbcTemplate, private val json: JsonCodec) {
    fun entries(owner: String): List<Record> = db.query("SELECT id::text,payload,created_at FROM investment_cash_ledger WHERE owner_id=? ORDER BY created_at,id", { r,_ ->
        json.read(r.getString(2))+mapOf("id" to r.getString(1),"createdAt" to r.getTimestamp(3).toInstant().toString())
    },owner)
    @Transactional fun save(owner: String,p: Record): Record {
        lockAccount(db,owner)
        val id=p.text("id"); checkApi(uuid(id),"Invalid transaction identity.")
        val existing=entries(owner).firstOrNull { it.text("id")==id }
        if(existing!=null) {
            checkApi(listOf("kind","date","currency","holdingId","shares","price","fees","tax","amount","note").all { key ->
                p[key]==null || p[key].toString()==existing[key]?.toString() || (p[key] is Number && existing[key] is Number && p.decimal(key).compareTo(existing.decimal(key))==0)
            },"This transaction identity is already in use.",409)
            return existing
        }
        val kind=p.text("kind");val day=date(p.text("date"))
        checkApi(kind in listOf("deposit","withdrawal","dividend","sale") && day!=null && day<=LocalDate.now(ZoneOffset.ofHours(2)),"Choose a transaction type and a date that is not in the future.")
        val currency=p.text("currency");checkApi(Regex("[A-Z]{3}").matches(currency),"Choose a three-letter currency.")
        checkApi(p.text("note").length<=500,"Keep the note under 500 characters.")
        fun number(key: String,zero: Boolean=false): BigDecimal {
            checkApi(p[key] is Number || (zero && p[key]==null),"Enter valid transaction amounts.")
            val value=p.decimal(key)
            checkApi(value>=BigDecimal.ZERO && value<=BigDecimal("1000000000000000") && value.stripTrailingZeros().scale()<=6,"Check transaction precision and amounts.")
            return value
        }
        var result: Record=mapOf("kind" to kind,"date" to p.text("date"),"currency" to currency,"note" to p.text("note").trim())
        if(kind in listOf("sale","dividend")) {
            val lot=db.query("SELECT holding FROM equity_purchases WHERE owner_id=? AND id::text=?",{r,_->json.read(r.getString(1))},owner,p.text("holdingId")).singleOrNull()
                ?: throw ApiError(404,"Share purchase not found.")
            checkApi(lot.text("currency")==currency && p.text("date")>=lot.text("tradeDate"),"Use the purchase currency and a date on or after purchase.")
            result=result+mapOf("holdingId" to lot["id"],"ticker" to lot["ticker"])
            if(kind=="sale") {
                val quantity=number("shares");val price=number("price");val fees=number("fees",true);val tax=number("tax",true)
                val sold=entries(owner).filter { it.text("kind")=="sale" && it.text("holdingId")==lot.text("id") }.sumOf { it.decimal("shares") }
                checkApi(lot.text("status")=="active" && quantity>BigDecimal.ZERO && quantity.stripTrailingZeros().scale()<=0 && quantity<=lot.decimal("shares")-sold && price>BigDecimal.ZERO,"You cannot sell more shares than remain.",409)
                val gross=quantity*price;checkApi(gross<=BigDecimal("1000000000000000") && fees+tax<=gross,"Sale charges cannot exceed proceeds.")
                val remainingCost=lot.decimal("netAmountPayable")-entries(owner).filter { it.text("kind")=="sale" && it.text("holdingId")==lot.text("id") }.sumOf { it.decimal("allocatedCost") }
                val allocated=if(quantity.compareTo(lot.decimal("shares")-sold)==0) remainingCost
                    else lot.decimal("netAmountPayable").multiply(quantity).divide(lot.decimal("shares"),2,RoundingMode.HALF_UP).min(remainingCost)

                val net=(gross-fees-tax).setScale(2,RoundingMode.HALF_UP)
                result=result+mapOf("shares" to quantity,"price" to price,"fees" to fees,"tax" to tax,"amount" to net,"allocatedCost" to allocated,"realizedGain" to net-allocated)
            }
        }
        if(kind!="sale") { val amount=number("amount");checkApi(amount>BigDecimal.ZERO,"Enter a positive amount.");result=result+mapOf("amount" to amount) }
        db.update("INSERT INTO investment_cash_ledger(id,owner_id,payload) VALUES(?::uuid,?,?::jsonb)",id,owner,json.write(result))
        return entries(owner).single { it.text("id")==id }
    }
    @Transactional fun delete(owner: String,id: String): Record {
        lockAccount(db,owner);checkApi(uuid(id),"Invalid transaction identity.")
        checkApi(db.update("DELETE FROM investment_cash_ledger WHERE owner_id=? AND id=?::uuid",owner,id)>0,"Transaction not found.",404)
        return mapOf("deleted" to true)
    }
}

@RestController
@RequestMapping("/api/bonds/cash-ledger")
class InvestmentLedgerController(private val ledger: InvestmentLedger) {
    @GetMapping fun list()=mapOf("entries" to ledger.entries(currentAccount().id))
    @PostMapping fun save(@RequestBody p: Record)=mapOf("entry" to ledger.save(currentAccount().id,p))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: String)=ledger.delete(currentAccount().id,id)
}
