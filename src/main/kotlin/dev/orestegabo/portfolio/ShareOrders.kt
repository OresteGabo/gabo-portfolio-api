package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Requested orders are deliberately never stored in equity_purchases or included in valuations. */
@Service
class ShareOrders(private val db: JdbcTemplate, private val json: JsonCodec, private val portfolio: PortfolioService) {
    fun list(owner: String): List<Record> = db.query("SELECT data FROM share_orders WHERE owner_id=? AND status='pending' ORDER BY updated_at DESC,id", { r,_ -> json.read(r.getString(1)) },owner)
    private fun find(owner: String,id: String): Record? = db.query("SELECT data FROM share_orders WHERE owner_id=? AND id=?::uuid FOR UPDATE", { r,_ -> json.read(r.getString(1)) },owner,id).singleOrNull()

    @Transactional fun save(owner: String,id: String,raw: Record): Record {
        checkApi(uuid(id),"Invalid order id.")
        lockAccount(db,owner)
        val previous=find(owner,id)
        checkApi(previous == null || previous.text("status")=="pending","This order is already closed.",409)
        checkApi(raw.number("version")== (previous?.number("version") ?: 0.0),"This order changed on another device. Refresh before editing.",409)
        checkApi(previous!=null || list(owner).size<200,"Finish an existing pending order first.")
        val data=validateShareOrder(raw)+mapOf("id" to id,"status" to "pending","version" to ((previous?.number("version") ?: 0.0).toInt()+1))
        db.update("INSERT INTO share_orders(owner_id,id,status,data) VALUES (?,?::uuid,'pending',?::jsonb) ON CONFLICT(owner_id,id) DO UPDATE SET data=excluded.data,updated_at=now()",owner,id,json.write(data))
        return data
    }
    @Transactional fun cancel(owner: String,id: String,raw: Record): Record {
        checkApi(uuid(id),"Invalid order id.");lockAccount(db,owner)
        val previous=find(owner,id) ?: throw ApiError(404,"Order not found.")
        if(previous.text("status")=="cancelled")return previous
        checkApi(previous.text("status")=="pending" && raw.number("version")==previous.number("version"),"This order changed. Refresh before continuing.",409)
        val data=previous+mapOf("status" to "cancelled","version" to previous.number("version").toInt()+1)
        db.update("UPDATE share_orders SET status='cancelled',data=?::jsonb,updated_at=now() WHERE owner_id=? AND id=?::uuid",json.write(data),owner,id)
        return data
    }
    /** This records a completed trade supplied by the user; it never places a broker order. */
    @Transactional fun confirm(owner: String,id: String,raw: Record): Record {
        checkApi(uuid(id) && raw["confirmed"]==true,"Confirm the executed purchase details.");lockAccount(db,owner)
        val previous=find(owner,id) ?: throw ApiError(404,"Order not found.")
        if(previous.text("status")=="executed") return portfolio.shares(owner).firstOrNull { it.text("id")==previous.text("holdingId") } ?: throw ApiError(409,"The recorded purchase is no longer available.")
        checkApi(previous.text("status")=="pending" && raw.number("version")==previous.number("version"),"This order changed. Refresh before continuing.",409)
        val purchase=raw["purchase"].record()
        checkApi(purchase.text("ticker").trim().uppercase()==previous.text("ticker") && purchase.decimal("shares").compareTo(previous.decimal("shares"))==0,"Record the full requested quantity for this security. Edit the pending order first if the quantity changed.",409)
        val holding=portfolio.saveShare(owner,purchase)
        val data=previous+mapOf("status" to "executed","holdingId" to holding.text("id"),"version" to previous.number("version").toInt()+1)
        db.update("UPDATE share_orders SET status='executed',data=?::jsonb,updated_at=now() WHERE owner_id=? AND id=?::uuid",json.write(data),owner,id)
        return holding
    }
}

internal fun validateShareOrder(raw: Record): Record {
    fun text(key: String,max: Int,required: Boolean=false): String = raw.text(key).trim().also { checkApi(it.length<=max && (!required || it.isNotBlank()),"Check the order details.") }
    val ticker=text("ticker",10,true).uppercase()
    checkApi(ticker.matches(Regex("[A-Z][A-Z0-9]{1,9}")),"Enter a valid ticker.")
    val shares=raw.decimal("shares");val price=raw.decimal("pricePerShare")
    checkApi(shares>BigDecimal.ZERO && shares<=BigDecimal("1000000000") && shares.stripTrailingZeros().scale()<=0 && price>BigDecimal.ZERO && price<=BigDecimal("1000000000") && price.stripTrailingZeros().scale()<=2,"Enter a positive whole number of shares and a valid price.")
    val requested=text("requestDate",10,true);val expected=text("expectedExecutionDate",10)
    checkApi(runCatching { LocalDate.parse(requested).toString()==requested && (expected.isBlank() || LocalDate.parse(expected).toString()==expected && expected>=requested) }.getOrDefault(false),"Check the order dates.")
    val gross=(shares*price).setScale(2,RoundingMode.HALF_UP)
    checkApi(gross<=BigDecimal("10000000000000"),"The order amount is too large.")
    val fees=(gross*BigDecimal("0.0171")).setScale(2,RoundingMode.HALF_UP)
    return mapOf("ticker" to ticker,"securityName" to text("securityName",160,true),"shares" to shares,"pricePerShare" to price,"requestDate" to requested,"expectedExecutionDate" to expected,"broker" to text("broker",120),"brokerConfirmed" to (raw["brokerConfirmed"]==true),"notes" to text("notes",1000),"currency" to "RWF","exchange" to "RSE","commissionRate" to BigDecimal("0.0171"),"grossConsideration" to gross,"estimatedCommission" to fees,"estimatedTotal" to gross+fees)
}

@RestController
@RequestMapping("/api/bonds/share-orders")
class ShareOrdersController(private val orders: ShareOrders) {
    @GetMapping fun list()=mapOf("orders" to orders.list(currentAccount().id))
    @PutMapping("/{id}") fun save(@PathVariable id: String,@RequestBody body: Record)=mapOf("order" to orders.save(currentAccount().id,id,body))
    @PostMapping("/{id}/cancel") fun cancel(@PathVariable id: String,@RequestBody body: Record)=mapOf("order" to orders.cancel(currentAccount().id,id,body))
    @PostMapping("/{id}/confirm") fun confirm(@PathVariable id: String,@RequestBody body: Record)=mapOf("holding" to orders.confirm(currentAccount().id,id,body))
}
