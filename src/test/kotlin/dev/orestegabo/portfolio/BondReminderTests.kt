package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.LocalDate

class BondReminderTests {
    private val issue=mapOf("id" to "issue-2027-03", "title" to "Treasury bond", "openBookDate" to "2027-03-15", "closingBookDate" to "2027-03-19")
    @Test fun formReadinessNeedsAnActualPublishedFormAndCorrectMonth() {
        assertTrue(bondReminderMilestones(issue,null,LocalDate.parse("2027-03-01")).isEmpty())
        val form=mapOf("id" to "form", "publishedDate" to "2027-02-27")
        assertTrue(bondReminderMilestones(issue,form,LocalDate.parse("2027-02-28")).isEmpty())
        val events=bondReminderMilestones(issue,form,LocalDate.parse("2027-03-01"))
        assertEquals(listOf("form"),events.map { it.text("stage") })
        assertEquals("2027-03-01",events.single().text("eventDate"))
        assertEquals("2027-03",events.single().text("month"))
    }
    @Test fun openClosingAndClosedAreDistinctIdempotentMilestones() {
        assertEquals(listOf("open"),bondReminderMilestones(issue,null,LocalDate.parse("2027-03-15")).map { it.text("stage") })
        assertEquals(listOf("open","closing"),bondReminderMilestones(issue,null,LocalDate.parse("2027-03-18")).map { it.text("stage") })
        val events=bondReminderMilestones(issue,null,LocalDate.parse("2027-03-20"))
        assertEquals(listOf("open","closing","closed"),events.map { it.text("stage") })
        assertEquals(listOf("closed"),events.filter { bondReminderDeliverable(it,LocalDate.parse("2027-03-20")) }.map { it.text("stage") })
        assertTrue(events.none { bondReminderDeliverable(it,LocalDate.parse("2027-03-21")) })
    }
    @Test fun livePayloadUsesUnixTimeAndNeverExceedsEightHoursOfFreshness() {
        val event=bondReminderMilestones(issue,null,LocalDate.parse("2027-03-15")).single()
        val aps=bondLiveStartPayload(event,false,1_000_000L)["aps"].record()
        assertEquals("BondSubscriptionAttributes",aps.text("attributes-type"))
        assertEquals(1_028_800L,aps["stale-date"])
        assertEquals(event.text("endsAt").toDouble(),aps["content-state"].record()["endsAt"])
    }
}
