package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

/** Owner-only account inventory and aggregate valuations; no extra private records leave the endpoint. */
@Service
class OwnerAccountPortfolios(private val db: JdbcTemplate, private val portfolio: PortfolioService,
    private val orders: ShareOrders, private val market: MarketService) {
    fun accounts(owner: Account,friend: Account?): List<Record> {
        val accounts = mutableListOf<Record>(mapOf("id" to owner.id,"email" to owner.email,"createdAt" to null,"status" to "active","kind" to "owner","deletable" to false))
        friend?.let { accounts+=mapOf("id" to it.id,"email" to it.email,"createdAt" to null,"status" to "active","kind" to "environment","deletable" to false) }
        accounts += db.queryForList("SELECT id,email,created_at::text AS \"createdAt\",CASE WHEN disabled_at IS NULL THEN 'active' ELSE 'disabled' END AS status FROM bond_users ORDER BY created_at DESC,email").map { it+mapOf("kind" to "database","deletable" to (it["email"]!=owner.email && it["id"]!=friend?.id)) }
        val quotes = try { market.quotes("equities") } catch (_: RuntimeException) { emptyMap<String, Any?>() }
        val today = notificationToday()
        return accounts.map { a ->
            val b=portfolio.bonds(a.text("id")); val s=portfolio.shares(a.text("id"))
            a+mapOf("portfolio" to mapOf("valuation" to accountPortfolioValues(b, s, orders.list(a.text("id")), quotes, today),"purchaseCount" to b.size,"principalByCurrency" to b.groupBy { it.text("currency") }.map { (c,rows)->mapOf("currency" to c,"amount" to rows.sumOf { it.decimal("faceValue") }) },"latestPurchase" to b.firstOrNull()?.filterKeys { it in listOf("bondName","isin","purchaseDate","createdAt") },"equityActivity" to mapOf("purchaseCount" to s.size,"cashCost" to s.sumOf { it.decimal("netAmountPayable") },"latestSecurity" to s.maxByOrNull { it.text("createdAt") }?.let { "${it.text("securityName")} (${it.text("ticker")})" })))
        }
    }
}

/** Mirrors the mobile dashboard basis: principal + remaining shares, including pending estimates.
 * Currencies are never converted. Missing/non-RWF/future-dated quotes fall back to recorded cash cost.
 * BigDecimal keeps aggregation exact; this endpoint does not compute returns or coupon projections.
 */
internal fun accountPortfolioValues(bonds: List<Record>, holdings: List<Record>, orders: List<Record>,
    quotes: Record, today: LocalDate): Record {
    val bondPositions = bonds.filter { it.text("status") == "submitted" ||
        it.text("status") == "active" && date(it.text("purchaseDate"))?.let { d -> d <= today } == true }
    val shares = holdings.filter { it.text("status") == "active" &&
        date(it.text("tradeDate"))?.let { d -> d <= today } == true }.map { lot ->
        lot + mapOf("shares" to (if (lot.containsKey("remainingShares")) lot.decimal("remainingShares") else lot.decimal("shares")),
            "netAmountPayable" to (if (lot.containsKey("remainingCost")) lot.decimal("remainingCost") else lot.decimal("netAmountPayable")))
    }.filter { it.decimal("shares").signum() > 0 } + orders.filter { it.text("status") == "pending" }.map {
        it + mapOf("netAmountPayable" to it.decimal("estimatedTotal"))
    }
    val priceDate = date(quotes["source"].record().text("publishedAt").take(10))?.takeIf { it <= today }
    val prices = if (priceDate != null) quotes.records("quotes") else emptyList()
    val currencies = (bondPositions + shares).map { it.text("currency") }.distinct().sorted().map { currency ->
        val principal = bondPositions.filter { it.text("currency") == currency }.sumOf { it.decimal("faceValue") }
        var unpriced = 0
        val equity = shares.filter { it.text("currency") == currency }.groupBy { it.text("ticker") }.map { (ticker,lots) ->
            val price = prices.firstOrNull { currency == "RWF" && it.text("ticker").trim().uppercase() == ticker.trim().uppercase() && it.decimal("closing").signum() > 0 }?.decimal("closing")
            if (price != null) lots.sumOf { it.decimal("shares") } * price
            else { unpriced++; lots.sumOf { it.decimal("netAmountPayable") } }
        }.fold(BigDecimal.ZERO, BigDecimal::add)
        mapOf("currency" to currency,"bondValue" to principal,"shareValue" to equity,
            "estimatedNetWorth" to principal + equity,"unpricedShares" to unpriced)
    }
    return mapOf("currencies" to currencies,"priceDate" to priceDate?.toString(),
        "stale" to (quotes["source"].record()["stale"] == true || priceDate == null),"includesPending" to true)
}
