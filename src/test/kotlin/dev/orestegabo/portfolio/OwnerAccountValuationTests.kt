package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.LocalDate

class OwnerAccountValuationTests {
    private val today = LocalDate.parse("2030-06-15")
    private fun bond(status: String, amount: Int, purchase: String = "2030-01-01", currency: String = "RWF"): Record =
        mapOf("status" to status,"currency" to currency,"faceValue" to amount,"purchaseDate" to purchase)
    private fun share(status: String = "active", currency: String = "RWF", ticker: String = "TEST"): Record =
        mapOf("status" to status,"currency" to currency,"ticker" to ticker,"tradeDate" to "2030-01-01",
            "shares" to 100,"netAmountPayable" to BigDecimal("73231.2"))
    private fun quotes(day: String = "2030-06-14", price: BigDecimal = BigDecimal("720.25")): Record =
        mapOf("source" to mapOf("publishedAt" to day,"stale" to true),"quotes" to listOf(mapOf("ticker" to "TEST","closing" to price)))
    private fun only(result: Record) = result.records("currencies").single()
    private fun amount(expected: String, row: Record, field: String) = assertEquals(0, BigDecimal(expected).compareTo(row.decimal(field)), field)

    @Test fun principalAndRemainingSharesIncludePendingButExcludeClosedAndFuturePurchases() {
        val b = listOf(bond("active",1000000),bond("submitted",200000),bond("sold",900000),bond("matured",900000),bond("active",900000,"2031-01-01"))
        val s = listOf(share()+mapOf("remainingShares" to 60,"remainingCost" to 43938.72),share("sold"),share()+mapOf("remainingShares" to 0),share()+mapOf("tradeDate" to "2031-01-01"))
        val orders = listOf(share("pending")+mapOf("shares" to 100,"estimatedTotal" to 73231.2),share("cancelled"))
        val result = accountPortfolioValues(b,s,orders,quotes(),today)
        val row = only(result)
        amount("1200000",row,"bondValue"); amount("115240.00",row,"shareValue"); amount("1315240",row,"estimatedNetWorth")
        assertEquals(0,row["unpricedShares"]); assertEquals(true,result["includesPending"]); assertEquals("2030-06-14",result["priceDate"])
    }
    @Test fun absentInvalidOrFutureQuotesUseRecordedCostWithoutInventingZeroPrices() {
        for(q in listOf(emptyMap(),quotes("2031-01-01"),quotes(price=BigDecimal.ZERO),quotes(price=BigDecimal("-1")))) {
            val row=only(accountPortfolioValues(emptyList(),listOf(share()),emptyList(),q,today))
            amount("73231.2",row,"shareValue"); assertEquals(1,row["unpricedShares"])
        }
    }
    @Test fun currenciesStaySeparateAndNonRwfPricesUseRemainingCost() {
        val rows=accountPortfolioValues(listOf(bond("active",25,currency="USD")),listOf(share(),share(currency="USD")+mapOf("remainingShares" to 50,"remainingCost" to BigDecimal("120.30"))),emptyList(),quotes(),today).records("currencies")
        amount("72025",rows.single { it.text("currency")=="RWF" },"estimatedNetWorth")
        amount("145.30",rows.single { it.text("currency")=="USD" },"estimatedNetWorth")
        assertEquals(1,rows.single { it.text("currency")=="USD" }["unpricedShares"])
    }
    @Test fun emptyAccountsHaveNoInventedHoldingsAndFractionalTotalsStayExact() {
        assertTrue(accountPortfolioValues(emptyList(),emptyList(),emptyList(),quotes(),today).records("currencies").isEmpty())
        val row=only(accountPortfolioValues(emptyList(),List(100) { share()+mapOf("shares" to BigDecimal("0.1")) },emptyList(),quotes(price=BigDecimal("0.1")),today))
        amount("1",row,"shareValue")
    }
}
