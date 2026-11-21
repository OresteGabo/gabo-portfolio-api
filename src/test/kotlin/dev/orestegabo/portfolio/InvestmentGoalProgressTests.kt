package dev.orestegabo.portfolio

import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InvestmentGoalProgressTests {
    private val today = LocalDate.parse("2026-11-21")
    @Test fun yearlyProgressUsesCalendarContributionsAndPendingEstimatesInsteadOfPrices() {
        val bonds = listOf(mapOf("status" to "submitted","currency" to "RWF","purchaseDate" to "2026-01-01","faceValue" to 100000,"pricePercent" to 100,"feesPaid" to 11000))
        val shares = listOf(mapOf("status" to "sold","currency" to "RWF","tradeDate" to "2026-03-01","netAmountPayable" to 100000),
            mapOf("status" to "active","currency" to "RWF","tradeDate" to "2025-12-01","netAmountPayable" to 999999))
        val orders = listOf(mapOf("status" to "pending","currency" to "RWF","requestDate" to "2026-11-01","estimatedTotal" to 20000))
        val p = goalContributionProgress(bonds,shares,orders,mapOf("goalPeriod" to "yearly","yearlyTarget" to 300000),today)
        assertEquals(100000.0,p.number("confirmed")); assertEquals(131000.0,p.number("pending"))
        val month = goalContributionProgress(bonds,shares,orders,mapOf("monthlyTarget" to 100000),today)
        assertEquals(0.0,month.number("confirmed")); assertEquals(20000.0,month.number("pending"))
        val reset = goalContributionProgress(bonds,shares,orders,mapOf("goalPeriod" to "yearly"),LocalDate.parse("2027-01-01"))
        assertEquals(0.0,reset.number("confirmed")); assertEquals(0.0,reset.number("pending"))
    }
}
