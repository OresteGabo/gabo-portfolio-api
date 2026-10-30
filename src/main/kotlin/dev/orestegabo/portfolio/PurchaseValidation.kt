package dev.orestegabo.portfolio

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI

@Component
class PurchaseValidation(json: JsonCodec) {
    private val catalog: List<Record> = javaClass.getResourceAsStream("/bond-catalog.json")!!.bufferedReader().use { json.list(it.readText()) }
    fun catalogEntry(p: Record): Record? = catalog.firstOrNull {
        (p.text("isin").isNotEmpty() && normalized(it.text("isin")) == normalized(p.text("isin"))) ||
        normalized(it.text("issuanceNumber")) == normalized(p.text("bondName")) ||
        (it.number("tenorYears") == p.number("tenorYears") && it.text("settlementDate") == p.text("settlementDate") &&
            (it.text("maturityDate") == p.text("maturityDate") || it.text("purchaseDate") == p.text("purchaseDate")))
    }
    fun canonical(p: Record): Record {
        if (p.text("status") == "submitted") return p
        val entry = catalogEntry(p) ?: return p
        return p + mapOf("bondName" to entry["issuanceNumber"], "isin" to p.text("isin").ifEmpty { entry.text("isin") },
            "couponRate" to if (p.number("couponRate") == 0.0) entry["couponRate"] else p["couponRate"],
            "firstCouponDate" to p.text("firstCouponDate").ifEmpty { entry.text("firstCouponDate") },
            "couponDates" to p.strings("couponDates").ifEmpty { entry.strings("couponDates") }, "scheduleConfidence" to "confirmed")
    }
    fun bond(raw: Record): Record {
        val type = raw.text("instrumentType", "treasury")
        val p = linkedMapOf<String,Any?>("instrumentType" to type, "issuer" to raw.text("issuer", if(type == "treasury") "Government of Rwanda" else "").trim(),
            "currency" to raw.text("currency", "RWF").trim().uppercase(), "market" to raw.text("market", "primary"),
            "purchaseDate" to raw.text("purchaseDate"), "settlementDate" to raw.text("settlementDate",raw.text("purchaseDate")),
            "bondName" to raw.text("bondName").trim(), "isin" to raw.text("isin").trim().uppercase(),
            "maturityDate" to raw.text("maturityDate"), "firstCouponDate" to raw.text("firstCouponDate"),
            "couponDates" to raw.strings("couponDates").distinct().sorted(), "scheduleConfidence" to raw.text("scheduleConfidence", if(type == "treasury") "confirmed" else "estimated"),
            "broker" to raw.text("broker").trim(), "accountReference" to raw.text("accountReference").trim(),
            "sourceUrl" to raw.text("sourceUrl").trim(), "status" to raw.text("status", "active"), "notes" to raw.text("notes").trim())
        val error = "Check the purchase details and try again."
        for (key in listOf("tenorYears","faceValue","pricePercent","accruedInterestPaid","feesPaid","couponRate","withholdingTaxRate","couponFrequency")) {
            val supplied = when {
                key == "withholdingTaxRate" && type == "treasury" && raw[key] == null -> BigDecimal("0.05")
                key in listOf("accruedInterestPaid","feesPaid","withholdingTaxRate") && raw[key] == null -> BigDecimal.ZERO
                else -> raw[key]
            }
            val n = (supplied as? Number)?.toString()?.toBigDecimalOrNull() ?: throw ApiError(400,error)
            checkApi(n.abs() <= BigDecimal("10000000000000"), error)
            p[key] = n
        }
        checkApi(type in listOf("treasury","government","corporate","municipal","other") && p.text("issuer").length in 1..160 && Regex("[A-Z]{3}").matches(p.text("currency")) && p.text("market") in listOf("primary","secondary","other"),error)
        val purchase = date(p.text("purchaseDate")); val settlement = date(p.text("settlementDate")); val maturity = date(p.text("maturityDate"))
        checkApi(purchase != null && settlement != null && maturity != null && maturity > purchase,error)
        val first = p.text("firstCouponDate")
        checkApi(first.isEmpty() || date(first)?.let { it > purchase!! && it <= maturity!! } == true,error)
        checkApi(p.strings("couponDates").all { date(it)?.let { d -> d > purchase!! && d <= maturity!! } == true },error)
        checkApi(p.text("bondName").length in 1..120 && p.text("isin").length<=32 && p.number("tenorYears")>0 && p.number("tenorYears")<=100,error)
        checkApi(p.number("faceValue")>0 && (type!="treasury" || p.decimal("faceValue").remainder(BigDecimal("100000")).signum() == 0),error)
        checkApi(p.number("pricePercent")>0 && p.number("pricePercent")<=1000 && p.number("accruedInterestPaid")>=0 && p.number("feesPaid")>=0 && p.number("couponRate") in 0.0..1.0 && p.number("withholdingTaxRate") in 0.0..1.0,error)
        val frequency = p.decimal("couponFrequency")
        checkApi(frequency.stripTrailingZeros().scale()<=0 && p.number("couponFrequency") in 1.0..12.0,error)
        checkApi(12%frequency.toInt()==0 || p.strings("couponDates").isNotEmpty(),"Provide published coupon dates for this frequency.")
        checkApi(p.text("scheduleConfidence") in listOf("confirmed","estimated") && (p.text("scheduleConfidence")!="confirmed" || p.strings("couponDates").isNotEmpty()),error)
        checkApi(p.text("broker").length<=120 && p.text("accountReference").length<=120 && p.text("sourceUrl").length<=1000 && p.text("notes").length<=1000 && p.text("status") in listOf("submitted","active","sold","matured"),error)
        checkApi(p.text("sourceUrl").isEmpty() || runCatching { URI(p.text("sourceUrl")).let { it.scheme in listOf("http","https") && it.host != null } }.getOrDefault(false),error)
        p["amountInvested"] = (p.decimal("faceValue")*p.decimal("pricePercent")/BigDecimal(100)+p.decimal("accruedInterestPaid")+p.decimal("feesPaid")).setScale(2,RoundingMode.HALF_UP)
        return p
    }
    fun equity(raw: Record): Record {
        val error = "Check the share details, dates and fee totals before saving."
        val p = linkedMapOf<String,Any?>("instrumentType" to "equity", "exchange" to "RSE", "currency" to "RWF", "status" to "active", "notes" to "")
        for ((key,max) in mapOf("securityName" to 160,"isin" to 32,"ticker" to 10,"broker" to 120,"contractNote" to 120,"cdsAccountNo" to 120,"cdsReference" to 120,"tradeReportId" to 120)) {
            checkApi(raw[key] == null || raw[key] is String,error)
            val text = raw.text(key).trim(); checkApi(text.length<=max,error); p[key]=text
        }
        checkApi(raw.text("instrumentType")=="equity" && raw.text("exchange")=="RSE" && raw.text("currency")=="RWF" && p.text("securityName").isNotBlank() && Regex("[A-Z][A-Z0-9]{1,9}").matches(p.text("ticker")) && Regex("[A-Z0-9]{8,32}").matches(p.text("isin")),error)
        val trade = date(raw.text("tradeDate")); val settle = date(raw.text("settlementDate"))
        checkApi(trade != null && settle != null && settle>=trade,error)
        p["tradeDate"]=raw.text("tradeDate"); p["settlementDate"]=raw.text("settlementDate")
        for(key in listOf("shares","pricePerShare","grossConsideration","brokerageCommission","rseTransactionFee","csdTransactionLevy","cmaTransactionFee","totalCharges","netAmountPayable")) {
            val n = (raw[key] as? Number)?.toString()?.toBigDecimalOrNull() ?: throw ApiError(400,error)
            checkApi(n>=BigDecimal.ZERO && n<=BigDecimal("10000000000000"),error); p[key]=n
        }
        fun near(a: BigDecimal,b: BigDecimal) = (a.setScale(2,RoundingMode.HALF_UP)-b).abs()<=BigDecimal("0.02")
        checkApi(p.number("shares")>0 && p.decimal("shares").stripTrailingZeros().scale()<=0 && p.number("pricePerShare")>0,error)
        checkApi(near(p.decimal("shares")*p.decimal("pricePerShare"),p.decimal("grossConsideration")) && near(listOf("brokerageCommission","rseTransactionFee","csdTransactionLevy","cmaTransactionFee").sumOf { p.decimal(it) },p.decimal("totalCharges")) && near(p.decimal("grossConsideration")+p.decimal("totalCharges"),p.decimal("netAmountPayable")),error)
        return p
    }
    fun bondKey(p: Record) = listOf(normalized(catalogEntry(p)?.text("issuanceNumber") ?: p.text("bondName")),p.text("currency"),p.text("market"),if(p.text("market")=="primary") "" else p.text("purchaseDate"),p.text("settlementDate"),p.decimal("faceValue").setScale(2,RoundingMode.HALF_UP),p.decimal("pricePercent").setScale(6,RoundingMode.HALF_UP)).joinToString("|")
    fun equityKey(p: Record) = listOf(normalized(p.text("isin").ifEmpty { p.text("securityName") }),p.text("currency"),p.text("tradeDate"),p.text("settlementDate"),p.decimal("shares").stripTrailingZeros().toPlainString(),p.decimal("pricePerShare").setScale(2,RoundingMode.HALF_UP),p.decimal("netAmountPayable").setScale(2,RoundingMode.HALF_UP)).joinToString("|")
}
