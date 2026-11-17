package dev.orestegabo.portfolio

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class BondReminderIntegrationTests {
    @Autowired lateinit var db: JdbcTemplate
    @Autowired lateinit var json: JsonCodec
    @Autowired lateinit var reminders: BondReminders
    @Autowired lateinit var notifications: PriceNotifications
    companion object {
        @JvmStatic @DynamicPropertySource fun properties(p: DynamicPropertyRegistry) {
            p.add("gabo.admin-email") { "owner@example.test" }
            p.add("gabo.admin-password-hash") { Passwords.hash("synthetic-reminder-password") }
            for(key in listOf("gabo.friend-email","gabo.friend-password-hash","gabo.push.firebase-project","gabo.push.apple-team","gabo.push.apple-key-id","gabo.push.apple-key","gabo.import-web-directory")) p.add(key) { "" }
        }
    }
    @Test fun subscriptionEventsStayInAppUntilTheirMonthIsExplicitlySelected() {
        val owner="reminder-integration-"+newId()
        val other="other-reminder-"+newId()
        val device=newId()
        val payload=mapOf("month" to "2027-03","stage" to "open","calendarId" to "test","dueDate" to "2027-03-19")
        notifications.register(owner,mapOf("deviceId" to device,"platform" to "android","token" to "synthetic-reminder-token-$device"))
        try {
            notifications.event(owner,"subscriptions","test-open",LocalDate.parse("2027-03-15"),payload)
            notifications.queue()
            assertEquals(1,notifications.list(owner,null).records("notifications").size)
            assertEquals(0,db.queryForObject("SELECT count(*) FROM price_push_deliveries WHERE device_id=?::uuid",Int::class.java,device))
            db.update("INSERT INTO bond_month_reminders(owner_id,month) VALUES(?,?)",other,"2027-03")
            notifications.queue()
            assertEquals(0,db.queryForObject("SELECT count(*) FROM price_push_deliveries WHERE device_id=?::uuid",Int::class.java,device))
            db.update("INSERT INTO bond_month_reminders(owner_id,month) VALUES(?,?)",owner,"2027-03")
            notifications.queue();notifications.queue()
            assertEquals(1,db.queryForObject("SELECT count(*) FROM price_push_deliveries WHERE device_id=?::uuid",Int::class.java,device))
            reminders.update(owner,"2027-03",false)
            assertEquals(0,db.queryForObject("SELECT count(*) FROM price_push_deliveries WHERE device_id=?::uuid",Int::class.java,device))
            assertEquals(1,notifications.list(owner,null).records("notifications").size)
        } finally {
            db.update("DELETE FROM push_devices WHERE owner_id=?",owner)
            db.update("DELETE FROM notifications WHERE owner_id=?",owner)
            db.update("DELETE FROM bond_month_reminders WHERE owner_id IN (?,?)",owner,other)
        }
    }
    @Test fun datesBackfillTheInboxOnceWithoutInventingAForm() {
        val owner="reminder-milestones-"+newId()
        val calendar=javaClass.getResourceAsStream("/issuance-calendar.json")!!.bufferedReader().use { json.list(it.readText()) }
        val issue=calendar.first()
        val close=LocalDate.parse(issue.text("closingBookDate"))
        try {
            reminders.sync(owner,close.plusDays(1));reminders.sync(owner,close.plusDays(1))
            val events=notifications.list(owner,null).records("notifications").filter { it.text("calendarId")==issue.text("id") }
            assertEquals(setOf("open","closing","closed"),events.map { it.text("stage") }.toSet())
            assertEquals(3,events.size)
        } finally { db.update("DELETE FROM notifications WHERE owner_id=?",owner) }
    }
}
