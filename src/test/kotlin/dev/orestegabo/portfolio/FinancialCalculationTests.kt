package dev.orestegabo.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tools.jackson.databind.json.JsonMapper

/** Reference amounts are synthetic test records, never persisted as user investments. */
class FinancialCalculationTests {
    private val validation=PurchaseValidation(JsonCodec(JsonMapper.builder().build()))
    private fun bond(principal: Int=2200000,rate: Double=.115,tax: Any?=.05): Record = mapOf(
        "instrumentType" to "treasury","issuer" to "Government of Rwanda","currency" to "RWF","market" to "primary",
        "purchaseDate" to "2026-07-01","settlementDate" to "2026-07-01","maturityDate" to "2033-07-31",
        "bondName" to "SYNTHETIC-REFERENCE","isin" to "RWTESTREF0001","tenorYears" to 7,"faceValue" to principal,
        "pricePercent" to 100,"accruedInterestPaid" to 0,"feesPaid" to 11000,"couponRate" to rate,
        "withholdingTaxRate" to tax,"couponFrequency" to 2,"scheduleConfidence" to "estimated","status" to "active")
    private fun equity(shares: Int,price: Int,fees: List<Double>,payable: Double): Record = mapOf(
        "instrumentType" to "equity","exchange" to "RSE","currency" to "RWF","securityName" to "Synthetic security",
        "ticker" to "TEST","isin" to "RWTESTREF0002","tradeDate" to "2026-09-24","settlementDate" to "2026-09-28",
        "shares" to shares,"pricePerShare" to price,"grossConsideration" to shares*price,
        "brokerageCommission" to fees[0],"rseTransactionFee" to fees[1],"csdTransactionLevy" to fees[2],"cmaTransactionFee" to fees[3],
        "totalCharges" to fees.sumOf { BigDecimal.valueOf(it) },"netAmountPayable" to payable)
    @Test fun `reference coupons respect default explicit zero and custom withholding`() {
        assertEquals(BigDecimal("120175.00"),netCouponAmount(validation.bond(bond())))
        assertEquals(BigDecimal("57000.00"),netCouponAmount(validation.bond(bond(1000000,.12)+mapOf("tenorYears" to 10,"maturityDate" to "2036-08-31"))))
        assertEquals(BigDecimal("126500.00"),netCouponAmount(validation.bond(bond(tax=0))))
        assertEquals(BigDecimal("113850.00"),netCouponAmount(validation.bond(bond(tax=.10))))
        assertEquals(BigDecimal("120175.00"),netCouponAmount(validation.bond(bond(tax=null))))
        assertThrows(ApiError::class.java) { validation.bond(bond(tax=1.01)) }
    }
    @Test fun `coupon rounding preserves cents and taxes unrounded gross`() {
        assertEquals(BigDecimal("57001.00"),netCouponAmount(bond()+mapOf("faceValue" to BigDecimal("1000017.54"),"couponRate" to .12)))
        assertEquals(BigDecimal("0.00"),netCouponAmount(bond()+mapOf("faceValue" to BigDecimal("0.01"),"couponRate" to 1)))
        assertEquals(BigDecimal("120175.48"),netCouponAmount(bond()+mapOf("faceValue" to BigDecimal("2200008.8"))))
    }
    @Test fun `published dates override inference and generated months do not drift or truncate`() {
        val p=bond()+mapOf("maturityDate" to "2036-08-31")
        val published=listOf("2027-02-19","2027-08-20","2036-08-22")
        assertEquals(published,bondCouponSchedule(p+mapOf("couponDates" to published)).map { it.toString() })
        assertTrue(LocalDate.parse("2035-08-31") in bondCouponSchedule(p))
        assertTrue(LocalDate.parse("2032-02-29") in bondCouponSchedule(p))
        assertTrue(LocalDate.parse("2027-08-31") in bondCouponSchedule(p+mapOf("firstCouponDate" to "2026-08-31")))
        assertTrue(bondCouponSchedule(p+mapOf("couponFrequency" to 7)).isEmpty())
        assertEquals(1200,bondCouponSchedule(p+mapOf("couponFrequency" to 12,"purchaseDate" to "2000-01-01","maturityDate" to "2100-01-01")).size)
    }
    @Test fun `reference equities include fees exactly once and reject inconsistent totals`() {
        val imr=equity(400,90,listOf(540.0,50.4,18.0,7.2),36615.60)
        val bok=equity(500,670,listOf(5025.0,469.0,167.5,67.0),340728.50)
        val a=validation.equity(imr);val b=validation.equity(bok)
        assertEquals(0,BigDecimal("615.60").compareTo(a.decimal("totalCharges")))
        assertEquals(0,BigDecimal("36615.60").compareTo(a.decimal("netAmountPayable")))
        assertEquals(0,BigDecimal("5728.50").compareTo(b.decimal("totalCharges")))
        assertEquals(0,BigDecimal("340728.50").compareTo(b.decimal("netAmountPayable")))
        assertEquals(0,BigDecimal("377344.10").compareTo(a.decimal("netAmountPayable")+b.decimal("netAmountPayable")))
        assertThrows(ApiError::class.java) { validation.equity(imr+mapOf("netAmountPayable" to 37231.2)) }
    }
}
