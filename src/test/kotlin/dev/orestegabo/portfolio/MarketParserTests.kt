package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.time.LocalDate

class MarketParserTests {
    private fun table(vararg rows: List<String>)="<table><tbody>"+rows.joinToString("") { "<tr>"+it.joinToString("") { "<td>$it</td>" }+"</tr>" }+"</tbody></table>"
    @Test fun `market parsers preserve symbols prices and empty values`() {
        val rows=table(listOf("I&amp;M Bank Rwanda PLC IMR","115","115","0%","8,100"),listOf("Bank of Kigali BOK","700","690","1%","0"))
        val q=MarketParser.quotes(rows,true)
        assertEquals(listOf("IMR","BOK"),q.map { it["ticker"] });assertEquals(115.0,q.first()["closing"])
        assertEquals(8100.0,q.first()["volume"]);assertEquals(0.0,q.last()["volume"])
        assertTrue(MarketParser.quotes(table(listOf("No data","0","0")),true).isEmpty())
        assertNull(MarketParser.number("not 100"));assertNull(MarketParser.number("—"))
        assertEquals(1234.5,MarketParser.number("1,234.50"))
    }
    @Test fun `RSE publication timestamps use Kigali time and handle New Year`() {
        assertEquals(Instant.parse("2026-10-21T12:27:00Z"),MarketParser.published("Last updated - 21 October 14:27 GMT+2",Instant.parse("2026-10-21T14:00:00Z")))
        assertEquals(Instant.parse("2026-12-31T12:00:00Z"),MarketParser.published("Last updated - 31 December 14:00 GMT+2",Instant.parse("2027-01-01T12:00:00Z")))
        assertNull(MarketParser.published("Last updated - 31 February 14:00 GMT+2",Instant.parse("2026-03-01T12:00:00Z")))
    }
    @Test fun `bond yields and clean prices preserve the web formulas`() {
        assertEquals(.12,MarketParser.yield(100.0,.12,5.0,0.0),.000001)
        assertEquals(.114,MarketParser.yield(100.0,.12,5.0,.05),.000001)
        assertEquals(100.0,MarketParser.cleanPrice(.12,.12,LocalDate.parse("2031-10-23"),LocalDate.parse("2026-10-23")))
        assertEquals("fxd1202420yrsreopened",MarketParser.identity("FXD1/2024/20Yrs (Re-opened) TREASURY"))
    }
}
