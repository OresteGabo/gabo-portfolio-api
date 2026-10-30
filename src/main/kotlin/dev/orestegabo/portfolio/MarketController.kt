package dev.orestegabo.portfolio

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.time.Instant

@RestController
@RequestMapping("/api/bonds/rse")
class MarketController(private val market: MarketService,private val history: MarketHistory,private val notifications: PriceNotifications,private val pushes: PricePushSender,private val lifecycle: LifecycleNotifications,
    @Value("\${gabo.cron-secret:}") private val cronSecret: String,
    @Value("\${gabo.snapshot-secret:}") private val snapshotSecret: String) {
    @GetMapping("/quotes") fun quotes(@RequestParam kind: String,@RequestParam(required=false) refresh: String?): Record {
        val data=market.quotes(kind,refresh!=null && currentAccountOrNull()!=null)
        // Optional history writes must never make usable current quotes fail.
        if(kind=="equities") runCatching { history.record(data) }
        return data
    }
    @GetMapping("/market") fun market(@RequestParam(required=false) refresh: String?)=market.market(refresh!=null && currentAccountOrNull()!=null)
    @GetMapping("/equity-history") fun history(@RequestParam tickers: String,@RequestParam(defaultValue="all") period: String): Record {
        val symbols=tickers.split(',').distinct()
        checkApi(period in listOf("month","year","all"),"Choose month, year, or all history.")
        checkApi(symbols.size in 1..10 && symbols.all { Regex("[A-Z][A-Z0-9]{1,9}").matches(it) },"Choose up to ten stock symbols.")
        return history.history(symbols,period,currentAccountOrNull()?.id)
    }
    private fun authorize(value: String?,secret: String) { checkApi(secret.isNotBlank() && MessageDigest.isEqual((value ?: "").toByteArray(),"Bearer $secret".toByteArray()),"Unauthorized.",401) }
    @GetMapping("/daily") fun daily(@RequestHeader("Authorization",required=false) authorization: String?): ResponseEntity<Record> {
        authorize(authorization,cronSecret)
        val start=Instant.now()
        val result=runCatching {
            var data=market.quotes("equities",true)
            if(data["source"].record().text("status")!="available" || data["source"].record().text("publishedAt").isBlank()) data=market.quotes("equities",true)
            val saved=history.record(data)
            if(saved["status"]=="saved") {
                notifications.collect(saved.text("marketDate"))
                runCatching { pushes.deliver() }
            }
            saved
        }.getOrDefault(mapOf("status" to "collection-failed"))
        runCatching { lifecycle.daily() }
        runCatching { pushes.deliver() }
        runCatching { history.run(start,result.text("status"),result.text("marketDate").ifBlank { null }) }
        return ResponseEntity.status(if(result["status"]=="saved")200 else 503).body(result)
    }
    @GetMapping("/snapshot") fun snapshot(@RequestHeader("Authorization",required=false) authorization: String?): Record {
        authorize(authorization,snapshotSecret)
        val data=market.market(true)
        return data.filterKeys { it in listOf("fetchedAt","treasuryRowsAnalyzed","fixedIncomePagesFetched","tradesStatus") }+mapOf("trades" to data.records("trades").size)
    }
}
