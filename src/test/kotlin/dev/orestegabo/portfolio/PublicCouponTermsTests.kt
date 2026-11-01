package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PublicCouponTermsTests {
    private fun input(): Record=mapOf("isin" to "RWTEST123456/MN","issuer" to "Government of Rwanda","currency" to "RWF",
        "instrumentType" to "treasury","maturityDate" to "2033-07-08","couponRate" to .115,"couponFrequency" to 2,"tenorYears" to 7,
        "scheduleConfidence" to "confirmed","couponDates" to listOf("2027-01-15","2033-07-08"),
        "faceValue" to 2200000,"feesPaid" to 11000,"withholdingTaxRate" to .05,"settlementDate" to "2026-12-18",
        "ownerId" to "private-owner","confirmationDocumentId" to "private-pdf","broker" to "private-broker","accountReference" to "private-account")
    @Test fun `only public instrument facts leave a private record`() {
        val p=PublicCouponTerms.project(input())!!
        assertEquals(setOf("isin","issuer","currency","instrumentType","maturityDate","couponRate","couponFrequency","tenorYears","couponDates","source"),p.keys)
        assertEquals("RWTEST123456",p["isin"]);assertEquals(.115,p.number("couponRate"))
        assertEquals(listOf("2027-01-15","2033-07-08"),p.strings("couponDates"))
    }
    @Test fun `estimated dates and zero unknown coupon do not become published scheduled income`() {
        assertTrue(PublicCouponTerms.project(input()+mapOf("scheduleConfidence" to "estimated"))!!.strings("couponDates").isEmpty())
        assertNull(PublicCouponTerms.project(input()+mapOf("couponRate" to 0)))
        assertNull(PublicCouponTerms.project(input()+mapOf("issuer" to "Private company")))
    }
    @Test fun `partial schedules can extend an exact schedule but shifted dates cannot create extra coupons`() {
        val a=PublicCouponTerms.project(input())!!
        assertTrue(PublicCouponTerms.compatible(a,a+mapOf("couponDates" to listOf("2033-07-08"))))
        assertFalse(PublicCouponTerms.compatible(a,a+mapOf("couponDates" to listOf("2027-01-20","2033-07-08"))))
        assertFalse(PublicCouponTerms.compatible(a,a+mapOf("couponRate" to .12)))
    }
}
