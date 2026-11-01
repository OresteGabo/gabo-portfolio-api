package dev.orestegabo.portfolio

/** Explicit public projection: never serialize a private purchase or its source metadata wholesale. */
internal object PublicCouponTerms {
    fun project(p: Record): Record? {
        val isin=p.text("isin").uppercase().filterNot(Char::isWhitespace).removeSuffix("/MN")
        if(p.text("issuer")!="Government of Rwanda" || p.text("currency")!="RWF" ||
            p.text("instrumentType") !in listOf("treasury","government") || !Regex("RW[A-Z0-9]{8,30}").matches(isin) ||
            date(p.text("maturityDate"))==null || p.number("tenorYears",Double.NaN).let { !it.isFinite() || it<=0 || it>100 } || p.number("couponRate",Double.NaN).let { !it.isFinite() || it<=0 || it>1 } ||
            p.number("couponFrequency",Double.NaN).let { !it.isFinite() || it%1!=0.0 || it !in 1.0..12.0 })return null
        val dates=if(p.text("scheduleConfidence")=="confirmed") p.strings("couponDates").filter {
            date(it)!=null && it<=p.text("maturityDate")
        }.distinct().sorted().take(200) else emptyList()
        return mapOf("isin" to isin,"issuer" to "Government of Rwanda","currency" to "RWF",
            "instrumentType" to "treasury","maturityDate" to p.text("maturityDate"),"couponRate" to p.decimal("couponRate"),
            "couponFrequency" to p.number("couponFrequency").toInt(),"tenorYears" to p.number("tenorYears"),"couponDates" to dates,
            "source" to "document-extracted")
    }
    fun compatible(a: Record,b: Record) = a.decimal("couponRate").compareTo(b.decimal("couponRate"))==0 &&
        a.number("couponFrequency")==b.number("couponFrequency") &&
        (a.strings("couponDates").containsAll(b.strings("couponDates")) || b.strings("couponDates").containsAll(a.strings("couponDates")))
}
