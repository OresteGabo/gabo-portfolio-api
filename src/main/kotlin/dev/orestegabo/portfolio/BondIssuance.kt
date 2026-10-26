package dev.orestegabo.portfolio

/** Strict allowlist: a public report cannot persist investor quantities or personal data. */
object BondIssuance {
    fun key(p: Record) = "${p.text("isin")}|${p.text("settlementDate")}"
    fun validate(raw: Record): Record {
        val error="Some issuance details could not be verified. Choose a clearer BNR market results PDF."
        val p=linkedMapOf<String,Any?>()
        for(k in listOf("bondName","isin")) p[k]=raw.text(k).uppercase().replace(Regex("\\s"),"")
        checkApi(Regex("FXD[0-9]+/[0-9]{4}/[0-9]+YR").matches(p.text("bondName")) && Regex("RW[A-Z0-9]{8,30}/MN").matches(p.text("isin")),error)
        checkApi(raw.text("issuer")=="Government of Rwanda" && raw.text("currency")=="RWF",error)
        p.putAll(mapOf("issuer" to "Government of Rwanda","currency" to "RWF","instrumentType" to "treasury","market" to "primary"))
        for(k in listOf("issuanceDate","settlementDate","maturityDate","firstCouponDate")) {
            checkApi(date(raw.text(k))!=null,error);p[k]=raw.text(k)
        }
        checkApi(p.text("issuanceDate")<=p.text("settlementDate") && p.text("settlementDate")<p.text("maturityDate"),error)
        for(k in listOf("tenorYears","pricePercent","couponRate","yieldRate","amountOffered","appliedAmount","amountSold","bidCount")) {
            val n=raw.number(k,Double.NaN)
            checkApi(n.isFinite() && n>=0,error);p[k]=n
        }
        checkApi(p.number("tenorYears") in 1.0..100.0 && p.number("tenorYears")%1==0.0 && p.number("pricePercent")>0 && p.number("pricePercent")<=1000 && p.number("couponRate")<=1 && p.number("yieldRate")<=1 && p.number("amountOffered")>0 && p.number("amountSold")<=p.number("appliedAmount") && p.number("bidCount")%1==0.0,error)
        val dates=raw.strings("couponDates")
        checkApi(dates.size in 1..200 && dates==dates.distinct().sorted() && dates.all { date(it)!=null && it>p.text("settlementDate") && it<=p.text("maturityDate") } && dates.first()==p.text("firstCouponDate") && dates.last()==p.text("maturityDate"),error)
        p["couponDates"]=dates;p["couponFrequency"]=2
        p["scheduleConfidence"]=if(raw.text("scheduleConfidence")=="confirmed" && dates.size==p.number("tenorYears").toInt()*2) "confirmed" else "estimated"
        return p
    }
    fun details(p: Record): Record {
        val labels=linkedMapOf("isin" to "Security code","issuanceDate" to "Issuance date","settlementDate" to "Settlement","maturityDate" to "Maturity","tenorYears" to "Tenor (years)","pricePercent" to "Price (%)","couponRate" to "Coupon rate","yieldRate" to "Yield rate","amountOffered" to "Amount offered (RWF)","appliedAmount" to "Applied amount (RWF)","amountSold" to "Amount sold (RWF)","bidCount" to "Bids")
        val facts=labels.map { (k,label)->mapOf("label" to label,"value" to if(k in listOf("couponRate","yieldRate")) "${p.number(k)*100}%" else p[k].toString()) }
        return mapOf("summary" to "Public auction results. This report does not confirm a personal purchase.","facts" to facts,"sections" to listOf(mapOf("title" to "Coupon payment dates","items" to p.strings("couponDates").mapIndexed { i,d->mapOf("label" to "${i+1}","value" to d) })))
    }
}
