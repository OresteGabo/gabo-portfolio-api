package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@Service
class PriceNotifications(private val db: JdbcTemplate, private val json: JsonCodec) {
    // Run after the daily closing snapshot, never on every screen refresh.
    @Transactional
    fun collect(day: String): Int {
        val date=LocalDate.parse(day)
        val count=db.update("""WITH latest AS (
            SELECT q.*,p.observed_date previous_date,p.closing_price previous_price
            FROM equity_market_observations q JOIN LATERAL (
                SELECT observed_date,closing_price FROM equity_market_observations
                WHERE ticker=q.ticker AND observed_date<q.observed_date ORDER BY observed_date DESC LIMIT 1
            ) p ON true WHERE q.observed_date=?::date AND q.closing_price<>p.closing_price
        ), positions AS (
            SELECT owner_id,holding->>'ticker' ticker,sum((holding->>'shares')::numeric) shares,
                sum((holding->>'netAmountPayable')::numeric) cash_cost
            FROM equity_purchases WHERE holding->>'status'='active' AND holding->>'currency'='RWF'
                AND holding->>'exchange'='RSE' AND holding->>'tradeDate'<=? GROUP BY owner_id,holding->>'ticker'
        ) INSERT INTO notifications(owner_id,ticker,market_date,previous_date,previous_price,closing_price,shares,cash_cost,event_key)
            SELECT s.owner_id,q.ticker,q.observed_date,q.previous_date,q.previous_price,q.closing_price,s.shares,s.cash_cost,'price:'||q.ticker||':'||q.observed_date
            FROM latest q JOIN positions s USING(ticker) WHERE s.shares>0 AND COALESCE((SELECT (categories->>'prices')::boolean FROM notification_preferences WHERE owner_id=s.owner_id),true)
                AND NOT EXISTS(SELECT 1 FROM bond_users u WHERE u.id=s.owner_id AND u.disabled_at IS NOT NULL)
            ON CONFLICT(owner_id,ticker,market_date) DO NOTHING""",date.toString(),date.toString())
        queue()
        return count
    }
    fun list(owner: String,before: String?): Record {
        val cursor=before?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ApiError(400,"Invalid notification date.") }
        // Page by entire market day to avoid skipping equal-date notifications.
        val days=db.queryForList("SELECT DISTINCT market_date::text AS day FROM notifications WHERE owner_id=? AND COALESCE((SELECT (categories->>notifications.kind)::boolean FROM notification_preferences WHERE owner_id=notifications.owner_id),true) AND (?::date IS NULL OR market_date<?::date) ORDER BY day DESC LIMIT 15",owner,cursor?.toString(),cursor?.toString()).map { it.text("day") }
        val rows=if(days.isEmpty()) emptyList() else db.queryForList("""SELECT id::text,kind,payload,ticker,market_date::text AS date,previous_date::text AS "previousDate",
            previous_price AS "previousPrice",closing_price AS "closingPrice",shares,
            (closing_price-previous_price)*shares AS impact,
            (closing_price/previous_price-1)*100 AS percent,
            closing_price*shares-cash_cost AS "totalGain",(closing_price*shares/NULLIF(cash_cost,0)-1)*100 AS "totalGainPercent",
            read_at IS NOT NULL AS read FROM notifications WHERE owner_id=? AND market_date IN(SELECT jsonb_array_elements_text(?::jsonb)::date)
            ORDER BY market_date DESC,ticker""",owner,json.write(days))
        val unread=db.queryForObject("SELECT count(*) FROM notifications WHERE owner_id=? AND read_at IS NULL AND COALESCE((SELECT (categories->>notifications.kind)::boolean FROM notification_preferences WHERE owner_id=notifications.owner_id),true)",Int::class.java,owner) ?: 0
        val receipts=db.queryForList("SELECT purchase_id::text AS id,due_date::text AS date,received,method FROM coupon_receipts WHERE owner_id=?",owner)
        val categories=db.queryForList("SELECT categories FROM notification_preferences WHERE owner_id=?",owner).firstOrNull()?.get("categories")?.toString()?.let(json::read).orEmpty()
        val values=rows.filter { categories[it.text("kind")]!=false }.map { row ->
            val payload=json.read(row["payload"].toString())
            val receipt=receipts.firstOrNull { it["id"]==payload["purchaseId"] && it["date"]==payload["dueDate"] }
            row.filterKeys { it!="payload" }+payload+mapOf("received" to (receipt?.get("received") ?: false),"receiptMethod" to receipt?.get("method"))
        }
        return mapOf("serverDate" to notificationToday().toString(),"notifications" to values,"unreadCount" to unread,"nextBefore" to days.lastOrNull().takeIf { days.size==15 })
    }
    fun enabled(owner: String,kind: String): Boolean = db.queryForList("SELECT categories->>? AS enabled FROM notification_preferences WHERE owner_id=?",kind,owner).firstOrNull()?.text("enabled")!="false"
    fun event(owner: String,kind: String,key: String,day: LocalDate,payload: Record) {
        if(!enabled(owner,kind))return
        db.update("INSERT INTO notifications(owner_id,kind,event_key,market_date,payload) VALUES(?,?,?,?::date,?::jsonb) ON CONFLICT(owner_id,event_key) DO UPDATE SET payload=EXCLUDED.payload,read_at=NULL,created_at=now() WHERE notifications.payload->>'resolved'='true'",owner,kind,key,day.toString(),json.write(payload))
    }
    fun queue() {
        db.update("""INSERT INTO price_push_deliveries(notification_id,device_id)
            SELECT n.id,d.id FROM notifications n JOIN push_devices d ON d.owner_id=n.owner_id
            WHERE n.created_at>=now()-interval '1 day' AND n.read_at IS NULL
                AND COALESCE((SELECT (categories->>n.kind)::boolean FROM notification_preferences WHERE owner_id=n.owner_id),true)
            ON CONFLICT DO NOTHING""")
    }
    fun read(owner: String,ids: List<String>) {
        checkApi(ids.size in 1..200 && ids.all(::uuid),"Invalid notifications.")
        db.update("UPDATE notifications SET read_at=COALESCE(read_at,now()) WHERE owner_id=? AND id IN(SELECT jsonb_array_elements_text(?::jsonb)::uuid)",owner,json.write(ids))
    }
    @Transactional
    fun register(owner: String,p: Record) {
        lockAccount(db,owner)
        val platform=p.text("platform");val token=p.text("token");val device=p.text("deviceId")
        checkApi(uuid(device) && platform in listOf("android","ios") && token.length in 16..4096 && token.all { it.isLetterOrDigit() || it in "_:-" },"Invalid push device.")
        val environment=p.text("environment","production");val language=p.text("language","en")
        checkApi(environment in listOf("production","sandbox") && language in listOf("en","fr"),"Invalid push settings.")
        checkApi((db.queryForObject("SELECT count(*) FROM push_devices WHERE owner_id=? AND id<>?::uuid",Int::class.java,owner,device) ?: 0)<20,"Too many registered devices.",409)
        // A token belongs to exactly one signed-in account, including shared phones.
        db.update("DELETE FROM push_devices WHERE token=? AND id<>?::uuid",token,device)
        db.update("""INSERT INTO push_devices(id,owner_id,platform,token,environment,language) VALUES(?::uuid,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET owner_id=EXCLUDED.owner_id,platform=EXCLUDED.platform,token=EXCLUDED.token,
                environment=EXCLUDED.environment,language=EXCLUDED.language,updated_at=now()""",device,owner,platform,token,environment,language)
    }
    fun unregister(owner: String,device: String) { checkApi(uuid(device),"Invalid push device.");db.update("DELETE FROM push_devices WHERE id=?::uuid AND owner_id=?",device,owner) }
}

@RestController
@RequestMapping("/api/bonds/notifications")
class PriceNotificationController(private val notifications: PriceNotifications,private val lifecycle: LifecycleNotifications) {
    @GetMapping fun list(@RequestParam(required=false) before: String?): Record { val owner=currentAccount().id;lifecycle.sync(owner);return notifications.list(owner,before) }
    @PostMapping("/read") fun read(@RequestBody p: Record): Record { notifications.read(currentAccount().id,p.strings("ids"));return mapOf("saved" to true) }
    @PostMapping("/devices") fun register(@RequestBody p: Record): Record { notifications.register(currentAccount().id,p);return mapOf("saved" to true) }
    @DeleteMapping("/devices/{id}") fun unregister(@PathVariable id: String): Record { notifications.unregister(currentAccount().id,id);return mapOf("deleted" to true) }
}
