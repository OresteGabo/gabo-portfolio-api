package dev.orestegabo.portfolio

import java.math.BigDecimal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class StatementMatchingTests {
    private fun debit(): Record=mapOf("kind" to "bond","currency" to "RWF","broker" to "BK Capital","date" to "2026-10-26","amount" to 1200000)
    private fun bond(): Record=mapOf("id" to "synthetic-bond","instrumentType" to "treasury","status" to "active","currency" to "RWF","broker" to "BK Capital","settlementDate" to "2026-10-23","faceValue" to 1200000,"pricePercent" to 100,"accruedInterestPaid" to 0,"feesPaid" to 11000,"isin" to "RWTESTREF001")
    private fun equityItem(): Record=mapOf("kind" to "equity","currency" to "RWF","broker" to "BK Capital","date" to "2026-09-24","amount" to BigDecimal("36615.60"),"contractNote" to "10001#01","ticker" to "IMR","shares" to 400,"pricePerShare" to 90)
    private fun equity(): Record=equityItem()+mapOf("id" to "synthetic-equity","instrumentType" to "equity","status" to "active","tradeDate" to "2026-09-24","netAmountPayable" to BigDecimal("36615.600"),"shares" to BigDecimal("400.00"))
    @Test fun `same date and amount alone are candidates never automatic bond matches`() {
        assertFalse(StatementMatching.matches(debit(),bond()))
        assertTrue(StatementMatching.candidate(debit(),bond()))
        assertEquals("review-required",StatementMatching.status(debit(),listOf(bond())))
        assertFalse(StatementMatching.matches(debit()+mapOf("transactionReference" to "account"),bond()+mapOf("accountReference" to "account")))
    }
    @Test fun `bond identity figures and broker context permit a unique automatic match`() {
        val item=debit()+mapOf("isin" to "RWTESTREF001")
        assertTrue(StatementMatching.matches(item,bond()))
        assertEquals("matched",StatementMatching.status(item,listOf(bond())))
        assertEquals("review-required",StatementMatching.status(item,listOf(bond(),bond()+mapOf("id" to "other"))))
        assertFalse(StatementMatching.matches(item,bond()+mapOf("broker" to "Other broker")))
        assertFalse(StatementMatching.candidate(item,bond()+mapOf("isin" to "RWOTHER00001")))
    }
    @Test fun `bond statement purchase excludes separate commission and fee debits`() {
        assertTrue(StatementMatching.candidate(debit(),bond()))
        assertFalse(StatementMatching.candidate(debit()+mapOf("amount" to 1211000),bond()))
    }
    @Test fun `equity match uses references quantity date price and net payable with scale independent decimals`() {
        assertTrue(StatementMatching.matches(equityItem(),equity()))
        assertFalse(StatementMatching.matches(equityItem(),equity()+mapOf("shares" to 401)))
        assertFalse(StatementMatching.canConfirm(equityItem(),equity()+mapOf("shares" to 401)))
        assertTrue(StatementMatching.canConfirm(debit(),bond()))
        assertFalse(StatementMatching.matches(equityItem(),equity()+mapOf("tradeDate" to "2026-09-25")))
        assertFalse(StatementMatching.candidate(equityItem(),equity()+mapOf("contractNote" to "10002#01")))
        assertEquals("review-required",StatementMatching.status(equityItem(),listOf(equity()+mapOf("netAmountPayable" to 36600))))
    }
    @Test fun `pending inactive other currency and nonpurchase evidence cannot become matches`() {
        for(change in listOf(mapOf("status" to "submitted"),mapOf("currency" to "USD"),mapOf("status" to "cancelled")))assertFalse(StatementMatching.candidate(debit(),bond()+change))
        assertEquals("unmatched",StatementMatching.status(debit(),emptyList()))
        for(kind in listOf("fee","coupon","dividend","cash-receipt","other")) {
            val item=debit()+mapOf("kind" to kind)
            assertEquals("evidence-only",StatementMatching.status(item,listOf(bond())))
            assertFalse(StatementMatching.candidate(item,bond()))
        }
    }
    @Test fun `financial edits invalidate a manual decision fingerprint while equivalent numeric scales do not`() {
        assertEquals(StatementMatching.fingerprint(equity()),StatementMatching.fingerprint(equity()+mapOf("shares" to 400)))
        assertNotEquals(StatementMatching.fingerprint(equity()),StatementMatching.fingerprint(equity()+mapOf("shares" to 401)))
        assertNotEquals(StatementMatching.fingerprint(bond()),StatementMatching.fingerprint(bond()+mapOf("faceValue" to 1300000)))
    }
}
