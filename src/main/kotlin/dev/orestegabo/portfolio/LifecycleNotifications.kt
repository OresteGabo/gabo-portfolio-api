package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.math.BigDecimal
import java.math.RoundingMode

val notificationCategories=setOf("prices","coupons","maturities","subscriptions","pending","documents","targets","reports","security")
fun notificationToday(): LocalDate=LocalDate.now(ZoneOffset.ofHours(2))

/** Existing lifecycle rules, exposed as pure functions for cross-runtime financial fixtures. */
internal fun bondCouponSchedule(p: Record): List<LocalDate> {
    val start=date(p.text("purchaseDate")) ?: return emptyList()
    val end=date(p.text("maturityDate")) ?: return emptyList()
    val published=p.strings("couponDates")
    if(published.isNotEmpty()) return published.mapNotNull(::date).distinct().filter { it>start && it<=end }.sorted()
    val frequency=p.number("couponFrequency",2.0)
    if(frequency%1.0!=0.0 || frequency !in 1.0..12.0 || 12%frequency.toInt()!=0 || end<=start || end>start.plusYears(100)) return emptyList()
    val interval=12L/frequency.toLong()
    val dates=mutableListOf<LocalDate>()
    val first=date(p.text("firstCouponDate"))
    if(first!=null) {
        if(first>end || first<start.minusYears(100))return emptyList()
        var step=0L;var day: LocalDate=first
        while(day<=end) { if(day>start)dates+=day;day=first.plusMonths(interval * ++step) }
        if(dates.isNotEmpty() && dates.last()!=end)dates+=end
    } else {
        var step=0L;var day=end
        while(day>start) { dates+=day;day=end.minusMonths(interval * ++step) }
    }
    return dates.distinct().sorted()
}

internal fun netCouponAmount(p: Record): BigDecimal {
    val frequency=p.decimal("couponFrequency","2")
    require(frequency>BigDecimal.ZERO)
    return (p.decimal("faceValue")*p.decimal("couponRate")*(BigDecimal.ONE-p.decimal("withholdingTaxRate","0.05")))
        .divide(frequency,2,RoundingMode.HALF_UP)
}

@Service
class LifecycleNotifications(private val db: JdbcTemplate,
                             private val json: JsonCodec,
                             private val portfolio: PortfolioService,
                             private val statements: StatementService,
                             private val notifications: PriceNotifications,
                             transactionManager: org.springframework.transaction.PlatformTransactionManager)
{
    private val dailyTransactions=org.springframework.transaction.support.TransactionTemplate(transactionManager)
    private val calendar=javaClass.getResourceAsStream("/issuance-calendar.json")!!.bufferedReader().use { json.list(it.readText()) }
    fun preferences(owner: String): Record {
        val row=db.queryForList("SELECT categories,auto_coupon,monthly_target FROM notification_preferences WHERE owner_id=?",owner).firstOrNull()
        return mapOf("configured" to (row!=null),"categories" to notificationCategories.associateWith { row?.get("categories")?.toString()?.let(json::read)?.get(it) ?: true },
            "autoCoupon" to (row?.get("auto_coupon") ?: false),"monthlyTarget" to (row?.get("monthly_target") ?: 100000))
    }
    @Transactional fun update(owner: String,p: Record): Record {
        lockAccount(db,owner)
        val old=preferences(owner)
        val categories=if(p.containsKey("categories"))p["categories"].record() else old["categories"].record()
        checkApi(categories.keys==notificationCategories && categories.values.all { it is Boolean },"Invalid notification categories.")
        val auto=p["autoCoupon"] ?: old["autoCoupon"]
        val target=p["monthlyTarget"] ?: old["monthlyTarget"]
        checkApi(auto is Boolean && target is Number && target.toDouble().isFinite() && target.toDouble()%1==0.0 && target.toDouble() in 1.0..1e12,"Invalid notification settings.")
        db.update("""INSERT INTO notification_preferences(owner_id,categories,auto_coupon,auto_coupon_from,monthly_target) VALUES(?,?::jsonb,?,CASE WHEN ? THEN CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Kigali' ELSE NULL END,?)
            ON CONFLICT(owner_id) DO UPDATE SET categories=EXCLUDED.categories,auto_coupon=EXCLUDED.auto_coupon,
                auto_coupon_from=CASE WHEN EXCLUDED.auto_coupon AND NOT notification_preferences.auto_coupon THEN EXCLUDED.auto_coupon_from ELSE notification_preferences.auto_coupon_from END,
                monthly_target=EXCLUDED.monthly_target""",owner,json.write(categories),auto,auto,(target as Number).toLong())
        return preferences(owner)
    }
    fun schedule(p: Record): List<LocalDate> = bondCouponSchedule(p)
    private fun amount(p: Record): BigDecimal = netCouponAmount(p)
    fun receipts(owner: String): List<Record> = db.queryForList("""SELECT r.purchase_id::text AS "purchaseId",r.due_date::text AS "dueDate",r.amount,r.received,r.method,b.currency,r.updated_at::text AS "updatedAt" FROM coupon_receipts r JOIN bond_purchases b ON b.id=r.purchase_id WHERE r.owner_id=? ORDER BY r.due_date DESC""",owner)
    @Transactional fun receive(owner: String,id: String,due: String,received: Boolean,today: LocalDate=notificationToday()): Record {
        lockAccount(db,owner)
        val purchase=portfolio.bond(owner,id) ?: throw ApiError(404,"Purchase not found.")
        val day=date(due)
        checkApi(day!=null && day<=today && day in schedule(purchase) && purchase.text("status") in listOf("active","matured"),"Choose a coupon that is already due.")
        db.update("""INSERT INTO coupon_receipts(owner_id,purchase_id,due_date,amount,received,method) VALUES(?,?::uuid,?::date,?,?,'manual')
            ON CONFLICT(purchase_id,due_date) DO UPDATE SET received=EXCLUDED.received,method='manual',updated_at=now()""",owner,id,due,amount(purchase),received)
        db.update("UPDATE notifications SET read_at=COALESCE(read_at,now()) WHERE owner_id=? AND kind='coupons' AND payload->>'purchaseId'=? AND payload->>'dueDate'=?",owner,id,due)
        return mapOf("receipts" to receipts(owner))
    }
    @Transactional fun sync(owner: String,today: LocalDate=notificationToday()) {
        lockAccount(db,owner)
        val preferences=preferences(owner)
        val bonds=portfolio.bonds(owner);val shares=portfolio.shares(owner)
        val knownReceipts=receipts(owner).map { it.text("purchaseId")+":"+it.text("dueDate") }.toSet()
        val automatic=preferences["autoCoupon"]==true
        val autoFrom=if(automatic)db.queryForList("SELECT auto_coupon_from::text AS date FROM notification_preferences WHERE owner_id=?",owner).firstOrNull()?.text("date")?.let(::date) else null
        fun event(kind: String,key: String,data: Record)=notifications.event(owner,kind,key,today,data)
        for(p in bonds) {
            val id=p.text("id");val title=p.text("bondName")
            if(p.text("status")=="submitted") {
                if(date(p.text("purchaseDate"))?.plusDays(3)?.let { it<=today }==true && date(p.text("settlementDate"))?.let { it<=today }==true)
                    event("pending","pending:$id",mapOf("purchaseId" to id,"title" to title,"action" to "purchase"))
                continue
            }
            if(p.text("status") !in listOf("active","matured"))continue
            for(due in schedule(p)) {
                if(automatic && autoFrom!=null && due>=autoFrom && due<=today && "$id:$due" !in knownReceipts)
                    db.update("""INSERT INTO coupon_receipts(owner_id,purchase_id,due_date,amount,received,method)
                        VALUES(?,?::uuid,?::date,?,true,'automatic') ON CONFLICT DO NOTHING""",owner,id,due.toString(),amount(p))
                val payload=mapOf("purchaseId" to id,"title" to title,"dueDate" to due.toString(),"amount" to amount(p),"currency" to p["currency"],"action" to "coupon")
                if(due>today && due<=today.plusDays(3))event("coupons","coupon-soon:$id:$due",payload+mapOf("stage" to "soon"))
                if(due<=today && due>=today.minusDays(30))event("coupons","coupon-due:$id:$due",payload+mapOf("stage" to "due"))
            }
            date(p.text("maturityDate"))?.takeIf { it>=today && it<=today.plusDays(30) }?.let {
                event("maturities","maturity:$id",mapOf("title" to title,"purchaseId" to id,"dueDate" to it.toString(),"amount" to p["faceValue"],"currency" to p["currency"],"action" to "calendar")) }
        }
        for(issue in calendar) {
            val open=date(issue.text("openBookDate"));val close=date(issue.text("closingBookDate"))
            if(open==null || close==null || today<open || today>close)continue
            val closing=today>=close.minusDays(1)
            event("subscriptions","subscription:${issue.text("id")}:${if(closing)"close" else "open"}",mapOf("title" to issue["title"],"stage" to if(closing)"closing" else "open","dueDate" to close.toString(),"action" to "calendar"))
        }
        val missing=statements.missing(owner)
        for(item in missing)event("documents","missing:${item.text("id")}",mapOf("statementId" to item["id"],"title" to item["title"],"action" to "documents"))
        db.update("""UPDATE notifications SET payload=payload||'{"resolved":true}'::jsonb,read_at=COALESCE(read_at,now())
            WHERE owner_id=? AND kind='documents' AND NOT COALESCE((payload->>'resolved')::boolean,false)
            AND payload->>'statementId' NOT IN(SELECT jsonb_array_elements_text(?::jsonb))""",owner,json.write(missing.map { it.text("id") }))
        db.update("""UPDATE notifications SET payload=payload||'{"resolved":true}'::jsonb,read_at=COALESCE(read_at,now())
            WHERE owner_id=? AND kind='pending' AND NOT COALESCE((payload->>'resolved')::boolean,false)
            AND payload->>'purchaseId' NOT IN(SELECT jsonb_array_elements_text(?::jsonb))""",owner,json.write(bonds.filter { it.text("status")=="submitted" }.map { it.text("id") }))
        val month=today.toString().take(7)
        val invested=bonds.filter { it.text("status")!="submitted" && it.text("currency")=="RWF" && it.text("purchaseDate").startsWith(month) && it.text("purchaseDate")<=today.toString() }.sumOf { it.number("amountInvested") }+
            shares.filter { it.text("currency")=="RWF" && it.text("tradeDate").startsWith(month) && it.text("tradeDate")<=today.toString() }.sumOf { it.number("netAmountPayable") }
        if(invested>=preferences.number("monthlyTarget",100000.0))event("targets","target:$month",mapOf("month" to month,"amount" to preferences["monthlyTarget"],"action" to "settings"))
        notifications.queue()
    }
    fun daily(today: LocalDate=notificationToday()) {
        val owners=db.queryForList("SELECT 'owner' AS id UNION SELECT id FROM bond_users WHERE disabled_at IS NULL UNION SELECT owner_id AS id FROM notification_preferences").map { it.text("id") }
        // Self-invocation bypasses @Transactional; give each account an actual rollback/lock boundary.
        for(owner in owners) runCatching { dailyTransactions.executeWithoutResult { sync(owner,today) } }
    }
    /** Registrations are security activity, not proof of currently active sessions. */
    fun signInActivity(owner: String,notificationId: String?): Record {
        val highlight=notificationId?.let { id ->
            checkApi(uuid(id),"Invalid security notification.")
            val key=db.query("SELECT event_key FROM notifications WHERE owner_id=? AND id=?::uuid AND kind='security'",{r,_->r.getString(1)},owner,id).firstOrNull()
                ?: throw ApiError(404,"Security notification not found.")
            key.removePrefix("device:").takeIf { key.startsWith("device:") && uuid(it) } ?: ""
        }.orEmpty()
        val devices=db.query("SELECT device_id::text,platform,first_seen FROM account_devices WHERE owner_id=? ORDER BY (device_id::text=?) DESC,first_seen DESC,device_id LIMIT 100",
            {r,_->mapOf("deviceId" to r.getString(1),"platform" to r.getString(2),"firstSeen" to r.getTimestamp(3).toInstant().toString())},owner,highlight)
        return mapOf("devices" to devices,"highlightDeviceId" to highlight)
    }
    @Transactional fun signIn(owner: String,device: String,platform: String) {
        if(!uuid(device) || platform !in listOf("ios","android"))return
        lockAccount(db,owner)
        val known=db.queryForObject("SELECT count(*) FROM account_devices WHERE owner_id=?",Int::class.java,owner) ?: 0
        val added=db.update("INSERT INTO account_devices(owner_id,device_id,platform) VALUES(?,?::uuid,?) ON CONFLICT DO NOTHING",owner,device,platform)
        if(known>0 && added>0)notifications.event(owner,"security","device:$device",notificationToday(),mapOf("platform" to platform,"action" to "settings"))
        notifications.queue()
    }
    @Transactional fun report(owner: String,p: Record) {
        lockAccount(db,owner)
        checkApi(Regex("[a-f0-9]{32}").matches(p.text("exportId")) && date(p.text("date"))!=null,"Invalid report event.")
        notifications.event(owner,"reports","report:${p.text("exportId")}",notificationToday(),mapOf("title" to "Portfolio report","reportDate" to p["date"],"action" to "settings"))
    }
}

@RestController
@RequestMapping("/api/bonds/notifications")
class LifecycleNotificationController(private val lifecycle: LifecycleNotifications,private val pushes: PricePushSender) {
    @PostMapping("/session-device") fun device(@RequestBody p: Record): Record { lifecycle.signIn(currentAccount().id,p.text("deviceId"),p.text("platform"));runCatching { pushes.deliver() };return mapOf("saved" to true) }
    @GetMapping("/sign-in-activity") fun signInActivity(@RequestParam(required=false) notificationId: String?)=lifecycle.signInActivity(currentAccount().id,notificationId)
    @GetMapping("/preferences") fun preferences()=lifecycle.preferences(currentAccount().id)
    @PutMapping("/preferences") fun update(@RequestBody body: Record)=lifecycle.update(currentAccount().id,body)
    @GetMapping("/coupons") fun coupons()=mapOf("receipts" to lifecycle.receipts(currentAccount().id))
    @PostMapping("/coupons") fun receive(@RequestBody body: Record): Record {
        checkApi(uuid(body.text("purchaseId")) && body["received"] is Boolean,"Invalid coupon receipt.")
        return lifecycle.receive(currentAccount().id,body.text("purchaseId"),body.text("dueDate"),body["received"] as Boolean)
    }
    @PostMapping("/report") fun report(@RequestBody body: Record): Record { lifecycle.report(currentAccount().id,body);return mapOf("saved" to true) }
}
