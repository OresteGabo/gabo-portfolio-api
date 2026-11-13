package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal

class ShareOrderTests {
    private fun order() = mapOf<String,Any?>("ticker" to "BOK","securityName" to "BK Group","shares" to 1000,"pricePerShare" to 720,"requestDate" to "2026-11-13","expectedExecutionDate" to "2026-11-16","brokerConfirmed" to true,"estimatedTotal" to 1,"commissionRate" to 0)
    @Test fun requestedExampleUsesFixedCommissionAndIgnoresClientTotals() {
        val p=validateShareOrder(order())
        assertEquals(BigDecimal("720000.00"),p["grossConsideration"])
        assertEquals(BigDecimal("12312.00"),p["estimatedCommission"])
        assertEquals(BigDecimal("732312.00"),p["estimatedTotal"])
        assertEquals(BigDecimal("0.0171"),p["commissionRate"])
        assertFalse(p.containsKey("tradeDate"))
    }
    @Test fun decimalCommissionRoundsHalfUpAndRejectsInvalidOrders() {
        assertEquals(BigDecimal("0.02"),validateShareOrder(order()+mapOf("shares" to 1,"pricePerShare" to 1))["estimatedCommission"])
        for (changes in listOf(mapOf("shares" to 1.5),mapOf("shares" to 0),mapOf("pricePerShare" to -1),mapOf("pricePerShare" to 1.001),mapOf("ticker" to "<invalid>"),mapOf("expectedExecutionDate" to "2026-11-12"),mapOf("requestDate" to "2026-02-30"))) {
            assertThrows(ApiError::class.java) { validateShareOrder(order()+changes) }
        }
    }
}
