package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import kotlin.test.*
import java.math.BigDecimal

class ImportEvidenceComparisonTests {
    private fun saved(): Record = mapOf(
        "bondName" to "SYNTHETIC/2028/2YR", "isin" to "RWTEST000001", "currency" to "RWF",
        "faceValue" to BigDecimal("1000000.00"), "pricePercent" to 100, "couponRate" to BigDecimal("0.12"),
        "feesPaid" to 11000, "withholdingTaxRate" to BigDecimal("0.05"),
        "settlementDate" to "2028-02-29", "maturityDate" to "2030-02-28",
        "couponDates" to listOf("2028-08-29", "2029-02-28", "2029-08-29", "2030-02-28"),
        "scheduleConfidence" to "confirmed", "broker" to "Test broker"
    )
    @Test fun scannedEvidenceWithIdenticalTermsDoesNotConflictWithConfirmedSavedSchedule() {
        val stored = saved()
        val scanned = stored + mapOf("scheduleConfidence" to "estimated", "faceValue" to 1000000,
            "notes" to "PDF recognized on device")
        assertTrue(sameFinancialRecord(stored, scanned))
        assertEquals("confirmed", stored["scheduleConfidence"])
    }
    @Test fun confidenceDifferenceNeverHidesChangedFinancialOrIdentityFields() {
        val stored = saved()
        for ((key, value) in mapOf(
            "isin" to "RWOTHER00001", "faceValue" to 1100000, "pricePercent" to 99,
            "couponRate" to BigDecimal("0.13"), "feesPaid" to 0,
            "withholdingTaxRate" to BigDecimal("0.15"), "settlementDate" to "2028-03-01",
            "maturityDate" to "2030-03-01", "broker" to "Other broker",
            "couponDates" to listOf("2028-08-30", "2029-02-28", "2029-08-29", "2030-02-28")
        )) {
            assertFalse(sameFinancialRecord(stored, stored + mapOf("scheduleConfidence" to "estimated", key to value)), key)
        }
    }
}
