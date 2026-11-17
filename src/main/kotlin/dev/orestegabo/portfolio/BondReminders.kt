package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.beans.factory.annotation.Value
import java.time.*
import java.security.MessageDigest

internal fun bondReminderMonth(issue: Record): String = issue.text("closingBookDate").take(7)
internal fun bondReminderMilestones(issue: Record, form: Record?, today: LocalDate): List<Record> {
    val open = date(issue.text("openBookDate")) ?: return emptyList()
    val close = date(issue.text("closingBookDate")) ?: return emptyList()
    if(close < open || today < close.withDayOfMonth(1) || today > close.plusDays(30)) return emptyList()
    val end = close.plusDays(1).atStartOfDay(ZoneId.of("Africa/Kigali")).toEpochSecond()
    val common = mapOf("calendarId" to issue.text("id"), "month" to bondReminderMonth(issue),
        "title" to issue.text("title"), "dueDate" to close.toString(), "openDate" to open.toString(),
        "endsAt" to end.toString(), "action" to "calendar")
    return buildList {
        if(form != null) {
            val published = form.text("publishedDate").let(::date) ?: today
            if(today >= published) add(common + mapOf("stage" to "form", "eventDate" to maxOf(published, close.withDayOfMonth(1)).toString(), "documentId" to form.text("id")))
        }
        if(today >= open) add(common + mapOf("stage" to "open", "eventDate" to open.toString()))
        if(today >= close.minusDays(1)) add(common + mapOf("stage" to "closing", "eventDate" to close.minusDays(1).toString()))
        if(today > close) add(common + mapOf("stage" to "closed", "eventDate" to close.plusDays(1).toString()))
    }
}
/** Current state only: delayed deliveries never announce a form/window that has already closed. */
internal fun bondReminderDeliverable(payload: Record, today: LocalDate): Boolean {
    val close = date(payload.text("dueDate")) ?: return false
    val open = date(payload.text("openDate")) ?: return false
    return when(payload.text("stage")) {
        "form" -> today <= close
        "open" -> today >= open && today < close.minusDays(1)
        "closing" -> today in close.minusDays(1)..close
        "closed" -> today == close.plusDays(1)
        else -> false
    }
}

@Service
class BondReminders(private val db: JdbcTemplate, private val json: JsonCodec, private val notifications: PriceNotifications) {
    private val calendar = javaClass.getResourceAsStream("/issuance-calendar.json")!!.bufferedReader().use { json.list(it.readText()) }
    fun list(owner: String): Record = mapOf("months" to db.queryForList("SELECT month FROM bond_month_reminders WHERE owner_id=? ORDER BY month", owner).map { it.text("month") })
    @Transactional fun update(owner: String, month: String, enabled: Boolean): Record {
        lockAccount(db,owner)
        checkApi(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month), "Choose a valid month and year.")
        if(enabled) {
            checkApi(calendar.any { bondReminderMonth(it) == month && (date(it.text("closingBookDate")) ?: LocalDate.MIN) >= notificationToday() }, "Choose a month with an upcoming subscription window.")
            db.update("INSERT INTO bond_month_reminders(owner_id,month) VALUES(?,?) ON CONFLICT DO NOTHING",owner,month)
            // Setting a reminder explicitly opts into the subscription category as well.
            db.update("UPDATE notification_preferences SET categories=jsonb_set(categories,'{subscriptions}','true'::jsonb) WHERE owner_id=?",owner)
            sync(owner)
            notifications.queue()
        } else {
            db.update("DELETE FROM bond_month_reminders WHERE owner_id=? AND month=?",owner,month)
            db.update("DELETE FROM price_push_deliveries q USING notifications n WHERE q.notification_id=n.id AND n.owner_id=? AND n.kind='subscriptions' AND n.payload->>'month'=? AND q.sent_at IS NULL",owner,month)
        }
        return list(owner)
    }
    fun sync(owner: String, today: LocalDate = notificationToday()) {
        val forms = db.queryForList("SELECT id::text,metadata,(created_at AT TIME ZONE 'Africa/Kigali')::date::text AS published FROM bond_application_forms")
        for(issue in calendar) {
            val form = forms.firstOrNull { json.read(it["metadata"].toString())["applicationForm"].record().text("calendarId") == issue.text("id") }
                ?.let { mapOf("id" to it.text("id"), "publishedDate" to it.text("published")) }
            for(event in bondReminderMilestones(issue,form,today)) {
                notifications.event(owner,"subscriptions","bond:${issue.text("id")}:${event.text("stage")}",LocalDate.parse(event.text("eventDate")),event)
                // Refresh schedule facts without changing read state or re-alerting an existing milestone.
                db.update("UPDATE notifications SET payload=?::jsonb WHERE owner_id=? AND event_key=? AND kind='subscriptions'",json.write(event),owner,"bond:${issue.text("id")}:${event.text("stage")}")
            }
        }
    }
    @Transactional fun activity(owner: String, p: Record): Record {
        val device=p.text("deviceId"); val token=p.text("token"); val id=p.text("activityId"); val issue=p.text("calendarId")
        checkApi(uuid(device) && id.matches(Regex("[A-Za-z0-9-]{1,120}")) && token.matches(Regex("[a-f0-9]{32,512}")),"Invalid live activity.")
        checkApi(calendar.any { it.text("id")==issue },"Unknown subscription.")
        checkApi(db.queryForObject("SELECT count(*) FROM push_devices WHERE id=?::uuid AND owner_id=? AND platform='ios'",Int::class.java,device,owner)==1,"Register this device first.",409)
        checkApi(db.queryForObject("SELECT count(*) FROM bond_month_reminders WHERE owner_id=? AND month=?",Int::class.java,owner,bondReminderMonth(calendar.first { it.text("id")==issue }))==1,"Reminder is no longer enabled.",409)
        db.update("INSERT INTO bond_live_activities(device_id,activity_id,calendar_id,token,expires_at) VALUES(?::uuid,?,?,?,now()+interval '8 hours') ON CONFLICT(device_id,activity_id) DO UPDATE SET token=EXCLUDED.token",device,id,issue,token)
        return mapOf("saved" to true)
    }
}

@RestController
@RequestMapping("/api/bonds/notifications")
class BondReminderController(private val reminders: BondReminders, private val lifecycle: LifecycleNotifications,
    private val pushes: PricePushSender, @Value("\${gabo.cron-secret:}") private val cronSecret: String) {
    @GetMapping("/bond-reminders") fun list() = reminders.list(currentAccount().id)
    @PutMapping("/bond-reminders/{month}") fun update(@PathVariable month: String,@RequestBody p: Record): Record {
        checkApi(p["enabled"] is Boolean,"Choose whether to enable this reminder.")
        return reminders.update(currentAccount().id,month,p["enabled"] as Boolean)
    }
    @PostMapping("/bond-live-activities") fun activity(@RequestBody p: Record) = reminders.activity(currentAccount().id,p)
    @GetMapping("/bond-reminders/dispatch") fun dispatch(@RequestHeader("Authorization",required=false) authorization: String?): Record {
        checkApi(cronSecret.isNotBlank() && MessageDigest.isEqual((authorization ?: "").toByteArray(),"Bearer $cronSecret".toByteArray()),"Unauthorized.",401)
        lifecycle.daily();pushes.deliver()
        return mapOf("processed" to true)
    }
}
