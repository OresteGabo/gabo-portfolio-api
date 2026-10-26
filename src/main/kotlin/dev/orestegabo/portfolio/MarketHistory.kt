package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.*

@Service
class MarketHistory(private val db: JdbcTemplate,private val json: JsonCodec) {
    fun record(data: Record): Record {
        val source=data["source"].record(); val quotes=data.records("quotes")
        if(source["stale"]==true || source.text("status")!="available" || quotes.isEmpty()) return mapOf("status" to "unusable-source")
        val published=runCatching { Instant.parse(source.text("publishedAt")) }.getOrNull()
        val checked=runCatching { Instant.parse(source.text("checkedAt")) }.getOrNull()
        if(published==null || checked==null || published>checked) return mapOf("status" to "missing-market-date")
        val day=published.atOffset(ZoneOffset.ofHours(2)).toLocalDate().toString()
        var written=0
        for(q in quotes.distinctBy { it.text("ticker") }) {
            if(!Regex("[A-Z][A-Z0-9]{1,9}").matches(q.text("ticker")) || q.number("closing")<=0) continue
            written+=db.update("""INSERT INTO equity_market_observations(ticker,observed_date,closing_price,observed_at,published_at) VALUES (?,?::date,?,?::timestamptz,?::timestamptz)
                ON CONFLICT(ticker,observed_date) DO UPDATE SET closing_price=EXCLUDED.closing_price,observed_at=EXCLUDED.observed_at,published_at=EXCLUDED.published_at WHERE equity_market_observations.observed_at<EXCLUDED.observed_at""",q["ticker"],day,q["closing"],checked.toString(),published.toString())
        }
        return mapOf("status" to "saved","marketDate" to day,"written" to written)
    }
    fun run(start: Instant,status: String,day: String?) {
        db.update("""INSERT INTO equity_market_collection(id,attempted_at,succeeded_at,market_date,status) VALUES(true,?::timestamptz,CASE WHEN ?='saved' THEN ?::timestamptz ELSE NULL END,?::date,?)
            ON CONFLICT(id) DO UPDATE SET attempted_at=EXCLUDED.attempted_at,succeeded_at=COALESCE(EXCLUDED.succeeded_at,equity_market_collection.succeeded_at),market_date=COALESCE(EXCLUDED.market_date,equity_market_collection.market_date),status=EXCLUDED.status WHERE equity_market_collection.attempted_at<=EXCLUDED.attempted_at""",start.toString(),status,start.toString(),day,status)
    }
    fun history(tickers: List<String>,period: String): Record {
        val today=LocalDate.now(ZoneOffset.ofHours(2)); val cutoff=when(period) { "month"->today.minusMonths(1); "year"->today.minusYears(1); else->LocalDate.of(1900,1,1) }
        val rows=db.queryForList("""WITH ranked AS (
            SELECT ticker,observed_date,closing_price,observed_at,count(*) OVER(PARTITION BY ticker) AS total,
            row_number() OVER(PARTITION BY ticker,date_trunc('month',observed_date) ORDER BY observed_date) AS first_in_month,
            row_number() OVER(PARTITION BY ticker,date_trunc('month',observed_date) ORDER BY observed_date DESC) AS last_in_month
            FROM equity_market_observations WHERE ticker IN(SELECT jsonb_array_elements_text(?::jsonb)) AND observed_date BETWEEN ?::date AND ?::date)
            SELECT ticker,observed_date::text AS date,closing_price AS closing,observed_at,total>400 AS aggregated FROM ranked WHERE total<=400 OR first_in_month=1 OR last_in_month=1 ORDER BY ticker,observed_date""",json.write(tickers),cutoff.toString(),today.toString())
        val observations=rows.map { mapOf("ticker" to it["ticker"],"date" to it["date"],"closing" to it["closing"],"observedAt" to (it["observed_at"] as java.sql.Timestamp).toInstant().toString()) }
        val trades=tradeHistory(tickers,cutoff.toString(),today.toString())
        return mapOf("available" to true,"resolution" to if(rows.any { it["aggregated"]==true })"monthly" else "daily","observations" to observations,"tradeHistoryAvailable" to true,"tradeObservations" to trades)
    }
    // Public price evidence only. Account identity, contracts, PDFs, quantities and totals never leave this projection.
    private fun tradeHistory(tickers: List<String>,from: String,through: String): List<Record> = db.queryForList("""WITH prices AS (
        SELECT DISTINCT holding->>'ticker' AS ticker,holding->>'tradeDate' AS date,
        CASE WHEN jsonb_typeof(holding->'pricePerShare')='number' THEN (holding->>'pricePerShare')::numeric END AS price
        FROM equity_purchases WHERE holding->>'exchange'='RSE' AND holding->>'currency'='RWF' AND length(trim(holding->>'contractNote'))>0 AND length(trim(holding->>'broker'))>0
        ), daily AS (SELECT ticker,date,percentile_disc(0.5) WITHIN GROUP(ORDER BY price) AS price,min(price) AS low,max(price) AS high FROM prices
        WHERE ticker IN(SELECT jsonb_array_elements_text(?::jsonb)) AND date BETWEEN ? AND ? AND price>0 AND price<=1e13 GROUP BY ticker,date), ranked AS (
        SELECT *,count(*) OVER(PARTITION BY ticker) AS total,row_number() OVER(PARTITION BY ticker,left(date,7) ORDER BY date) AS first,
        row_number() OVER(PARTITION BY ticker,left(date,7) ORDER BY date DESC) AS last,row_number() OVER(PARTITION BY ticker,left(date,7) ORDER BY low,date) AS lowest,
        row_number() OVER(PARTITION BY ticker,left(date,7) ORDER BY high DESC,date) AS highest FROM daily)
        SELECT ticker,date,price,low,high FROM ranked WHERE total<=400 OR first=1 OR last=1 OR lowest=1 OR highest=1 ORDER BY ticker,date""",json.write(tickers),from,through).filter { date(it.text("date"))!=null }.map { it+mapOf("source" to "contract") }
    fun recordBonds(trades: List<Record>,now: Instant) {
        for(t in trades) {
            val identity=MarketParser.identity(t.text("bond")); if(identity.isBlank())continue
            db.update("""INSERT INTO bond_market_observations(observed_date,observed_at,source_url,bond_name,normalized_bond_name,closing_price,previous_price,change_text,volume_text,value_text,raw_row)
                VALUES (?::date,?::timestamptz,'https://rse.rw/bond-market',?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING""",now.atOffset(ZoneOffset.UTC).toLocalDate().toString(),now.toString(),t["bond"],identity,MarketParser.number(t.text("closing"))?.takeIf { it>0 },MarketParser.number(t.text("previous"))?.takeIf { it>0 },t["change"],t["volume"],t["value"],json.write(t))
        }
    }
    fun recentBonds(): Map<String,Record> = db.queryForList("""WITH recent AS (SELECT * FROM bond_market_observations WHERE observed_date>=CURRENT_DATE-14),
        latest AS (SELECT DISTINCT ON(normalized_bond_name) * FROM recent ORDER BY normalized_bond_name,observed_at DESC)
        SELECT normalized_bond_name,observed_at,closing_price,change_text,volume_text,value_text,(SELECT count(*) FROM recent r WHERE r.normalized_bond_name=latest.normalized_bond_name) AS total FROM latest""").associate { it.text("normalized_bond_name") to mapOf("tradeCount" to it["total"],"lastObservedAt" to (it["observed_at"] as java.sql.Timestamp).toInstant().toString(),"lastClosingPrice" to it["closing_price"],"lastChange" to it["change_text"],"lastVolume" to it["volume_text"],"lastValue" to it["value_text"]) }
}
