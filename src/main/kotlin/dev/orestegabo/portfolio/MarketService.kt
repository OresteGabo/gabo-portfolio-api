package dev.orestegabo.portfolio

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

interface MarketPages { fun get(path: String): String }
@Component
class RsePages: MarketPages {
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    override fun get(path: String): String {
        require(path.startsWith("/") && !path.startsWith("//"))
        val request=HttpRequest.newBuilder(URI("https://rse.rw$path")).timeout(Duration.ofSeconds(8)).header("Accept","text/html").header("User-Agent","Gabo Portfolio optional market reader").GET().build()
        val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
        response.body().use {
            check(response.statusCode()==200)
            val bytes=it.readNBytes(1500001); check(bytes.size<=1500000)
            return String(bytes,Charsets.UTF_8)
        }
    }
}

object MarketParser {
    fun rows(html: String): List<List<String>> = Jsoup.parse(html).select("tbody tr").map { row-> row.select("td").map { it.text().trim() } }
    fun number(text: String): Double? = text.replace(",","").trim().takeIf { Regex("-?\\d+(\\.\\d+)?").matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 }
    fun quotes(html: String,equity: Boolean): List<Record> = rows(html).mapNotNull { row->
        if(row.size<2) return@mapNotNull null
        val closing=number(row[1])?.takeIf { it>0 && (equity || it<=1000) } ?: return@mapNotNull null
        val fields=mapOf("closing" to closing,"previous" to number(row.getOrElse(2){""}),"volume" to number(row.getOrElse(4){""}))
        if(!equity) fields+mapOf("bond" to row[0])
        else Regex("^(.+?)\\s+([A-Z][A-Z0-9]{1,9})$").find(row[0])?.let { fields+mapOf("name" to it.groupValues[1],"ticker" to it.groupValues[2]) }
    }
    fun published(html: String,now: Instant): Instant? = runCatching {
        val m=Regex("Last updated\\s*[-–:]?\\s*(\\d{1,2})\\s+([A-Za-z]+)\\s+(?:(\\d{4})\\s+)?(\\d{1,2}):(\\d{2})\\s+GMT\\+2",RegexOption.IGNORE_CASE).find(Jsoup.parse(html).text()) ?: return null
        val months=listOf("january","february","march","april","may","june","july","august","september","october","november","december")
        val year=m.groupValues[3].toIntOrNull() ?: now.atOffset(ZoneOffset.UTC).year
        val date=LocalDate.of(year,months.indexOf(m.groupValues[2].lowercase())+1,m.groupValues[1].toInt()).atTime(m.groupValues[4].toInt(),m.groupValues[5].toInt()).toInstant(ZoneOffset.ofHours(2))
        val corrected=if(m.groupValues[3].isEmpty() && date>now.plusSeconds(86400)) date.atOffset(ZoneOffset.ofHours(2)).minusYears(1).toInstant() else date
        corrected.takeIf { it<=now.plusSeconds(86400) }
    }.getOrNull()
    fun cleanBond(name: String)=name.replace(Regex("\\s*TREASURY\\s*$",RegexOption.IGNORE_CASE),"").trim()
    fun identity(name: String)=cleanBond(name).lowercase().replace(Regex("re-?opened"),"reopened").replace(Regex("[^a-z0-9]"),"")
    fun rseDate(text: String): LocalDate? {
        date(text)?.let { return it }
        for(pattern in listOf("d MMM yyyy","d MMMM yyyy","dd/MM/yyyy","dd-MM-yyyy")) runCatching { LocalDate.parse(text,DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)) }.getOrNull()?.let { return it }
        return null
    }
    fun yield(price: Double,rate: Double,years: Double,tax: Double): Double {
        val periods=max(1,ceil(years*2).toInt()); val coupon=100*rate*(1-tax)/2
        fun pv(y: Double)=(1..periods).sumOf { coupon/(1+y).pow(it) }+100/(1+y).pow(periods)
        var low=-.49; var high=1.0
        repeat(100) { val middle=(low+high)/2; if(pv(middle)>price) low=middle else high=middle }
        return (low+high)
    }
    fun cleanPrice(rate: Double,yield: Double,maturity: LocalDate,today: LocalDate): Double? {
        if(maturity<=today || rate<0 || yield<=-1) return null
        val dates=mutableListOf(maturity); var d=maturity
        while(d>today && dates.size<100) { d=d.minusMonths(6); if(d>today) dates.add(0,d) }
        val next=dates.first(); val previous=next.minusMonths(6)
        val fraction=java.time.temporal.ChronoUnit.DAYS.between(today,next).toDouble()/java.time.temporal.ChronoUnit.DAYS.between(previous,next)
        val coupon=100*rate/2; val y=yield/2
        val dirty=dates.indices.sumOf { coupon/(1+y).pow(fraction+it)+(if(it==dates.lastIndex) 100/(1+y).pow(fraction+it) else 0.0) }
        return ((dirty-coupon*(1-fraction))*1000).roundToLong()/1000.0
    }
}

@Service
class MarketService(private val pages: MarketPages,private val history: MarketHistory) {
    private data class Cached(val value: Record,val expires: Long,val checked: Long)
    private val cache=ConcurrentHashMap<String,Cached>()
    private val locks=mapOf("bonds" to Any(),"equities" to Any(),"market" to Any())
    private fun cached(key: String,refresh: Boolean,load: ()->Record): Record = synchronized(locks.getValue(key)) {
        val now=System.currentTimeMillis(); val old=cache[key]
        if(!refresh && old!=null && old.expires>now) return@synchronized old.value
        var fresh=load()
        val failed=if(key=="market") fresh.text("tradesStatus")=="error" && fresh.records("outstanding").isEmpty() else fresh["source"].record().text("status")=="error"
        if(failed && old!=null && now-old.checked<86400000 && key!="market") fresh=old.value+mapOf("source" to (old.value["source"].record()+mapOf("stale" to true)))
        cache[key]=Cached(fresh,now+if(failed)60000 else 900000,if(failed && old!=null)old.checked else now)
        fresh
    }
    private fun source(path: String,html: String?,count: Int,partial: Boolean=false): Record = mapOf("url" to "https://rse.rw/$path","status" to if(html==null)"error" else if(partial)"partial" else if(count==0)"empty" else "available","checkedAt" to Instant.now().toString(),"publishedAt" to null,"stale" to false)
    private fun pageSet(path: String,max: Int=8): Pair<List<String>,Boolean> {
        val first=pages.get("/$path")
        val count=Regex("page_bonds-trades=(\\d+)").findAll(first).map { it.groupValues[1].toIntOrNull() ?: 1 }.maxOrNull()?.coerceIn(1,max) ?: 1
        val result=mutableListOf(first); var failed=false
        for(i in 2..count) runCatching { pages.get("/$path?page_bonds-trades=$i"+(if(path=="fixed-income-board")"&category=TREASURY" else "")) }.onSuccess { result+=it }.onFailure { failed=true }
        return result to failed
    }
    fun quotes(kind: String,refresh: Boolean=false): Record {
        checkApi(kind in listOf("bonds","equities"),"Choose bonds or equities.")
        return cached(kind,refresh) {
            if(kind=="equities") {
                val now=Instant.now(); val html=runCatching { pages.get("/equities-market") }.getOrNull()
                val quotes=html?.let { MarketParser.quotes(it,true) } ?: emptyList()
                mapOf("source" to (source("equities-market",html,quotes.size)+mapOf("publishedAt" to html?.let { MarketParser.published(it,now)?.toString() })),"quotes" to quotes)
            } else {
                val market=runCatching { pageSet("bond-market") }.getOrNull(); val listing=runCatching { pageSet("outstanding-bonds") }.getOrNull()
                val quotes=market?.first?.flatMap { MarketParser.quotes(it,false) } ?: emptyList()
                val listings=listing?.first?.flatMap { MarketParser.rows(it) }?.filter { it.size>=2 && Regex("/[12]\\d{3}/").containsMatchIn(it[0]) }?.map { mapOf("bond" to it[0],"code" to it[1].uppercase()) } ?: emptyList()
                mapOf("source" to source("bond-market",market?.first?.firstOrNull(),quotes.size,market?.second==true),"listingSource" to source("outstanding-bonds",listing?.first?.firstOrNull(),listings.size,listing?.second==true),"quotes" to quotes,"listings" to listings)
            }
        }
    }
    fun market(refresh: Boolean=false): Record = cached("market",refresh) {
        val market=runCatching { pages.get("/bond-market") }.getOrNull()
        val fixed=runCatching { pageSet("fixed-income-board",25) }.getOrElse { runCatching { pageSet("outstanding-bonds") }.getOrDefault(emptyList<String>() to true) }
        val now=Instant.now(); val today=now.atOffset(ZoneOffset.UTC).toLocalDate()
        val trades=market?.let { MarketParser.rows(it) }?.filter { it.size>=6 }?.map { row->listOf("bond","closing","previous","change","volume","value").zip(row.take(6)).toMap()+mapOf("bond" to MarketParser.cleanBond(row[0])) } ?: emptyList()
        runCatching { history.recordBonds(trades,now) }
        val recent=runCatching { history.recentBonds() }.getOrDefault(emptyMap())
        val treasury=fixed.first.flatMap { MarketParser.rows(it) }.filter { it.size>=6 && it[0].contains("TREASURY",true) }
        val outstanding=treasury.mapNotNull { row->
            val maturity=MarketParser.rseDate(row[3]) ?: return@mapNotNull null
            val years=java.time.temporal.ChronoUnit.DAYS.between(today,maturity)/365.2425
            if(years<=0) return@mapNotNull null
            val bond=MarketParser.cleanBond(row[0]); val identity=MarketParser.identity(bond)
            val trade=trades.lastOrNull { MarketParser.identity(it.text("bond"))==identity }
            val closing=trade?.text("closing")?.let { MarketParser.number(it)?.takeIf { n->n>0 } }
            fun percent(value: String)=value.replace(Regex("[^\\d.-]"),"").toDoubleOrNull()?.div(100) ?: 0.0
            val rate=percent(row[4]); val published=percent(row[5])
            val gross=closing?.let { MarketParser.yield(it,rate,years,0.0) } ?: published
            val net=closing?.let { MarketParser.yield(it,rate,years,.05) } ?: published*.95
            val yieldScore=(net/.14*100).coerceIn(0.0,100.0); val durationScore=(years/12*100).coerceIn(0.0,100.0)
            val priceScore=if(closing==null)50.0 else if(closing<=100)100.0 else max(0.0,100-(closing-100)/100*500)
            val r=recent[identity]
            mapOf("bond" to bond,"code" to row[1],"issueDate" to row[2],"maturityDate" to row[3],"couponRate" to row[4],"yieldToMaturity" to row[5],"closingPrice" to closing,
                "impliedCleanPrice" to if(closing==null)MarketParser.cleanPrice(rate,published,maturity,today) else null,"grossYield" to gross,"netAnnualizedYield" to net,"yearsRemaining" to years,
                "strategyScore" to round((yieldScore*.7+durationScore*.25+priceScore*.05)*10)/10,"yieldScore" to yieldScore,"durationScore" to durationScore,"priceScore" to priceScore,"confidenceScore" to if(closing==null)60 else 100,
                "yieldSource" to if(closing==null)"RSE published YTM" else "closing-price estimate","recentTradeCount" to (r?.get("tradeCount") ?: if(closing==null)0 else 1),
                "lastTradedAt" to (r?.get("lastObservedAt") ?: if(closing==null)null else now.toString()),"lastTradedPrice" to (r?.get("lastClosingPrice") ?: closing),"lastTradeChange" to (r?.get("lastChange") ?: trade?.get("change") ?: ""),"lastTradeVolume" to (r?.get("lastVolume") ?: trade?.get("volume") ?: ""),"lastTradeValue" to (r?.get("lastValue") ?: trade?.get("value") ?: ""))
        }.distinctBy { it["code"] to it["yieldToMaturity"] }
        mapOf("trades" to trades,"tradesStatus" to if(market==null)"error" else if(trades.isEmpty())"empty" else "available","outstanding" to outstanding,"fixedIncomePagesFetched" to fixed.first.size,"treasuryRowsAnalyzed" to treasury.size,"fetchedAt" to if(fixed.first.isEmpty())null else now.toString())
    }
}
