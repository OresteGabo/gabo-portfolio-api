package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

/** Only bounded public form text is extracted; personal bidder fields are never used. */
internal fun applicationBidTerms(text: String, expected: Record): Record? = runCatching {
    require(text.length <= 200_000 && text.contains("TREASURY BOND APPLICATION FORM",true))
    fun dateField(label: String): String {
        val value = Regex("$label\\s*(?:\\([^)]*\\))?\\s*:\\s*(\\d{1,2})/(\\d{1,2})/(\\d{4})",RegexOption.IGNORE_CASE).find(text) ?: error("Missing date")
        return LocalDate.of(value.groupValues[3].toInt(),value.groupValues[2].toInt(),value.groupValues[1].toInt()).toString()
    }
    val title=Regex("T\\s*-?\\s*Bond\\s*n[°ºo.]*\\s*:\\s*([^\\r\\n]{1,100}?)\\s+DATED\\s+",RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim() ?: error("Missing identity")
    val tenor=Regex("Tenor\\s*:\\s*(\\d{1,2})\\s*years?",RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toInt() ?: error("Missing tenor")
    val issuance=dateField("Issuance date");val settlement=dateField("Settlement date");val maturity=dateField("(?:Redemption|Maturity) date")
    require(issuance==expected.text("issuanceDate") && settlement==expected.text("settlementDate") && maturity==expected.text("maturityDate") && tenor==expected.number("tenorYears").toInt())
    mapOf("bondName" to title,"tenorYears" to tenor,"issuanceDate" to issuance,"settlementDate" to settlement,"maturityDate" to maturity)
}.getOrNull()

@Service
class BondApplicationBidService(private val db: JdbcTemplate, private val forms: BondApplicationForms, private val portfolio: PortfolioService) {
    @Transactional fun create(owner: String, formId: String, p: Record, today: LocalDate = notificationToday()): Record {
        checkApi(uuid(formId) && p.keys==setOf("amount","bidId") && uuid(p.text("bidId")),"Review the bid amount and try again.")
        checkApi(p["amount"] is Number,"Use an amount in RWF 100,000 increments.")
        val amount=p.decimal("amount")
        checkApi(amount>=BigDecimal("100000") && amount<=BigDecimal("10000000000000") && amount.remainder(BigDecimal("100000")).signum()==0,"Use an amount in RWF 100,000 increments.")
        lockAccount(db,owner)
        val prior=db.queryForList("SELECT id::text,application_form_id::text,bid_amount FROM bond_purchases WHERE owner_id=? AND client_bid_id=?::uuid",owner,p["bidId"]).singleOrNull()
        if(prior!=null) {
            checkApi(prior.text("application_form_id")==formId && prior.decimal("bid_amount").compareTo(amount)==0,"This bid identity is already in use.",409)
            return portfolio.bond(owner,prior.text("id")) ?: throw ApiError(409,"The saved bid is unavailable. Refresh your portfolio.")
        }
        val form=forms.bidForm(formId) ?: throw ApiError(404,"Application form not found.")
        val issue=forms.calendarIssue(form["applicationForm"].record().text("calendarId"))
        val closing=date(issue.text("closingBookDate")) ?: throw ApiError(409,"The subscription window could not be verified.")
        checkApi(today>=closing.withDayOfMonth(1) && today<=closing,"This subscription window is closed or its issuance month has not started.",409)
        val terms=form["bidTerms"].record()
        checkApi(terms.isNotEmpty() && terms.text("issuanceDate")==issue.text("closingBookDate") && terms.text("settlementDate")==issue.text("settlementDate") && terms.text("maturityDate")==issue.text("maturityDate") && terms.number("tenorYears")==issue.number("tenorYears"),"The application form details could not be verified. Open the form or contact the owner.",409)
        val purchase=portfolio.saveBond(owner,mapOf("instrumentType" to "treasury","issuer" to "Government of Rwanda","currency" to "RWF","market" to "primary",
            "bondName" to terms["bondName"],"isin" to "","purchaseDate" to today.toString(),"settlementDate" to terms["settlementDate"],"maturityDate" to terms["maturityDate"],
            "tenorYears" to terms["tenorYears"],"faceValue" to amount,"pricePercent" to 100,"couponRate" to 0,"couponFrequency" to 2,"withholdingTaxRate" to BigDecimal("0.05"),
            "firstCouponDate" to "","couponDates" to emptyList<String>(),"scheduleConfidence" to "estimated","accruedInterestPaid" to 0,"feesPaid" to 0,"status" to "submitted",
            "notes" to "Pending bid. Allocation, executed price, fees and coupon are unconfirmed."))
        db.update("UPDATE bond_purchases SET application_form_id=?::uuid,client_bid_id=?::uuid,bid_amount=? WHERE id=?::uuid AND owner_id=?",formId,p["bidId"],amount,purchase["id"],owner)
        return portfolio.bond(owner,purchase.text("id"))!!
    }
}

@RestController
@RequestMapping("/api/bonds/application-forms")
class BondApplicationBidController(private val bids: BondApplicationBidService) {
    @PostMapping("/{id}/bids") fun create(@PathVariable id: String,@RequestBody body: Record)=mapOf("purchase" to bids.create(currentAccount().id,id,body))
}
