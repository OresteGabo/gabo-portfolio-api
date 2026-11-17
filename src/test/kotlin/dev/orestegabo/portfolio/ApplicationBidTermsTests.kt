package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

internal val syntheticApplicationText = """TREASURY BOND APPLICATION FORM
T-Bond no: FXD 6/2026/5YRS DATED 11/11/2026
Tenor: 5 years
Issuance date: 11/11/2026
Settlement date (T+2): 13/11/2026
Redemption date: 07/11/2031
Bidder: synthetic blank
Tender total amount: __________
"""
internal fun syntheticApplicationPdf(): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { document ->
    val page=org.apache.pdfbox.pdmodel.PDPage();document.addPage(page)
    org.apache.pdfbox.pdmodel.PDPageContentStream(document,page).use { content ->
        content.beginText();content.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),10f)
        content.newLineAtOffset(40f,740f)
        syntheticApplicationText.lines().filter { it.isNotBlank() }.forEach { content.showText(it);content.newLineAtOffset(0f,-18f) }
        content.endText()
    }
    java.io.ByteArrayOutputStream().use { output -> document.save(output);output.toByteArray() }
}
class ApplicationBidTermsTests {
    private val expected=mapOf("tenorYears" to 5,"issuanceDate" to "2026-11-11","settlementDate" to "2026-11-13","maturityDate" to "2031-11-07")
    @Test fun extractsOnlyPublishedIdentityAndDatesNeverAnInvestorAmountOrCoupon() {
        val terms=applicationBidTerms(syntheticApplicationText,expected)!!
        assertEquals("FXD 6/2026/5YRS",terms.text("bondName"));assertEquals(5,terms["tenorYears"])
        assertEquals(setOf("bondName","tenorYears","issuanceDate","settlementDate","maturityDate"),terms.keys)
    }
    @Test fun mismatchedMissingInvalidOrOversizedFormDetailsNeverBecomeBidTerms() {
        for(text in listOf(syntheticApplicationText.replace("13/11/2026","14/11/2026"),syntheticApplicationText.replace("5 years","7 years"),syntheticApplicationText.replace("11/11/2026","31/02/2026"),"Not an application", "x".repeat(200001))) assertNull(applicationBidTerms(text,expected))
    }
}
