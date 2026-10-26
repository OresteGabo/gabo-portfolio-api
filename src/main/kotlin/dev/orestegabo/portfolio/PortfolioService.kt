package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.math.BigDecimal

@Service
class PortfolioService(private val db: JdbcTemplate, private val json: JsonCodec, private val validation: PurchaseValidation) {
    private val bondFields = listOf("instrumentType","issuer","currency","market","purchaseDate","settlementDate","bondName","isin","tenorYears","faceValue","pricePercent","accruedInterestPaid","feesPaid","amountInvested","couponRate","withholdingTaxRate","maturityDate","firstCouponDate","couponDates","couponFrequency","scheduleConfidence","broker","accountReference","sourceUrl","status","notes")
    private fun column(key: String) = key.replace(Regex("[A-Z]")) { "_${it.value.lowercase()}" }
    private val dates = setOf("purchaseDate","settlementDate","maturityDate","firstCouponDate")
    fun bonds(owner: String): List<Record> = db.queryForList("SELECT * FROM bond_purchases WHERE owner_id=? ORDER BY purchase_date DESC,created_at DESC,id",owner).map { row ->
        val result = linkedMapOf<String,Any?>("id" to row["id"].toString(),"createdAt" to (row["created_at"] as java.sql.Timestamp).toInstant().toString())
        for(key in bondFields) result[key] = when(key) {
            "couponDates" -> json.mapper.readValue(row[column(key)].toString(),List::class.java)
            in dates -> row[column(key)]?.toString() ?: ""
            "currency" -> row[column(key)].toString().trim()
            else -> row[column(key)]
        }
        validation.canonical(result)
    }
    fun bond(owner: String,id: String) = bonds(owner).firstOrNull { it.text("id")==id }
    fun shares(owner: String): List<Record> = db.query("SELECT holding FROM equity_purchases WHERE owner_id=? ORDER BY holding->>'tradeDate' DESC,created_at DESC", { r,_ -> json.read(r.getString(1)) },owner)
    @Transactional fun saveBond(owner: String, raw: Record, id: String? = null): Record {
        lockAccount(db,owner)
        val purchase = validation.bond(raw)
        val existing = id?.let { checkApi(uuid(it),"Invalid purchase id."); bond(owner,it) ?: throw ApiError(404,"Purchase not found.") }
        val p = purchase + mapOf("id" to (id ?: newId()),"createdAt" to (existing?.get("createdAt") ?: Instant.now().toString()))
        val placeholders = bondFields.map { when(it) { "couponDates" -> "?::jsonb"; in dates -> "?::date"; else -> "?" } }
        val values = bondFields.map { key -> when(key) { "couponDates" -> json.write(p[key]); in dates -> p.text(key).ifEmpty { null }; else -> p[key] } }
        if(id==null) db.update("INSERT INTO bond_purchases(id,owner_id,dedup_key,${bondFields.joinToString(",") { column(it) }},created_at) VALUES (?::uuid,?,?,${placeholders.joinToString(",")},?::timestamptz)",*(listOf(p["id"],owner,validation.bondKey(p))+values+p["createdAt"]).toTypedArray())
        else db.update("UPDATE bond_purchases SET dedup_key=?,${bondFields.zip(placeholders).joinToString(",") { (key,holder) -> "${column(key)}=$holder" }},updated_at=now() WHERE owner_id=? AND id=?::uuid",*(listOf(validation.bondKey(p))+values+listOf(owner,id)).toTypedArray())
        return validation.canonical(p)
    }
    @Transactional fun deleteBond(owner: String,id: String): Boolean {
        lockAccount(db,owner); checkApi(uuid(id),"Invalid purchase id.")
        return db.update("DELETE FROM bond_purchases WHERE owner_id=? AND id=?::uuid",owner,id)>0
    }
    @Transactional fun saveShare(owner: String,raw: Record): Record {
        lockAccount(db,owner)
        val p = validation.equity(raw)+mapOf("id" to newId(),"createdAt" to Instant.now().toString())
        val contract = p.text("contractNote").takeIf { it.isNotBlank() }?.let { "${normalized(p.text("broker"))}|${normalized(it)}" }
        db.update("INSERT INTO equity_purchases(id,owner_id,dedup_key,contract_key,report_key,holding) VALUES (?::uuid,?,?,?,?,?::jsonb)",p["id"],owner,validation.equityKey(p),contract,p.text("tradeReportId").ifBlank { null },json.write(p))
        return p
    }
    fun accounts(owner: Account,friend: Account?): List<Record> {
        val accounts = mutableListOf<Record>(mapOf("id" to owner.id,"email" to owner.email,"createdAt" to null,"status" to "active","kind" to "owner","deletable" to false))
        friend?.let { accounts+=mapOf("id" to it.id,"email" to it.email,"createdAt" to null,"status" to "active","kind" to "environment","deletable" to false) }
        accounts += db.queryForList("SELECT id,email,created_at::text AS \"createdAt\",CASE WHEN disabled_at IS NULL THEN 'active' ELSE 'disabled' END AS status FROM bond_users ORDER BY created_at DESC,email").map { it+mapOf("kind" to "database","deletable" to (it["email"]!=owner.email && it["id"]!=friend?.id)) }
        return accounts.map { a ->
            val b=bonds(a.text("id")); val s=shares(a.text("id"))
            a+mapOf("portfolio" to mapOf("purchaseCount" to b.size,"principalByCurrency" to b.groupBy { it.text("currency") }.map { (c,rows)->mapOf("currency" to c,"amount" to rows.sumOf { it.decimal("faceValue") }) },"latestPurchase" to b.firstOrNull()?.filterKeys { it in listOf("bondName","isin","purchaseDate","createdAt") },"equityActivity" to mapOf("purchaseCount" to s.size,"cashCost" to s.sumOf { it.decimal("netAmountPayable") },"latestSecurity" to s.maxByOrNull { it.text("createdAt") }?.let { "${it.text("securityName")} (${it.text("ticker")})" })))
        }
    }
}

object MobileOverview {
    fun create(bonds: List<Record>,shares: List<Record>,today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Record {
        val from=today.minusYears(1).toString(); val through=today.toString()
        fun recent(rows: List<Record>,key: String)=rows.filter { it.text(key) in from..through }.sortedWith(compareByDescending<Record> { it.text(key) }.thenByDescending { it.text("createdAt") }.thenBy { it.text("id") }).take(12)
        val rb=recent(bonds,"purchaseDate"); val rs=recent(shares,"tradeDate")
        val bIds=rb.map { it["id"] }.toSet(); val sIds=rs.map { it["id"] }.toSet()
        val basis=bonds.filter { it.text("status")=="active" && it["id"] !in bIds }.map { it.filterKeys { k->k !in listOf("notes","sourceUrl","accountReference","createdAt") } }
        val positions=shares.filter { it.text("status")=="active" && it.text("tradeDate")<=through && it["id"] !in sIds }.groupBy { listOf(it.text("currency"),it.text("ticker")) }.map { (key,rows)-> mapOf("id" to "aggregate:[\"${key[0]}\",\"${key[1]}\"]","currency" to key[0],"ticker" to key[1],"status" to "active","tradeDate" to rows.minOf { it.text("tradeDate") },"shares" to rows.sumOf { it.decimal("shares") },"netAmountPayable" to rows.sumOf { it.decimal("netAmountPayable") },"lotCount" to rows.size) }
        return mapOf("version" to 1,"from" to from,"through" to through,"previewLimit" to 12,"monthlyInvestmentTotals" to totals(bonds,shares,today),"recentPurchaseIds" to bIds.toList(),"recentHoldingIds" to sIds.toList(),"purchases" to rb+basis,"holdings" to rs+positions)
    }
    fun totals(bonds: List<Record>,shares: List<Record>,today: LocalDate): List<Record> {
        val start=today.withDayOfMonth(1).minusMonths(11).toString(); val end=today.toString()
        val totals=sortedMapOf<String,MutableMap<String,Any?>>(reverseOrder())
        fun add(date: String,amount: BigDecimal,pending: Boolean) {
            if(date !in start..end || amount.signum()<0) return
            val row=totals.getOrPut(date.take(7)) { mutableMapOf("month" to date.take(7),"confirmed" to BigDecimal.ZERO,"pending" to BigDecimal.ZERO) }
            val k=if(pending) "pending" else "confirmed"; row[k]=row.decimal(k)+amount
        }
        for(p in bonds) if(p.text("currency")=="RWF" && p.text("status") in listOf("active","sold","matured","submitted")) add(p.text("purchaseDate"),p.decimal("faceValue")*p.decimal("pricePercent")/BigDecimal(100)+p.decimal("accruedInterestPaid")+p.decimal("feesPaid"),p.text("status")=="submitted")
        for(p in shares) if(p.text("currency")=="RWF" && p.text("status") in listOf("active","sold")) add(p.text("tradeDate"),p.decimal("netAmountPayable"),false)
        return totals.values.toList()
    }
}
