package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/** Synthetic outputs captured from the original website functions, not rewritten expectations. */
class WebCompatibilityTests {
    private val json=JsonCodec(JsonMapper.builder().build())
    private val fixture=javaClass.getResourceAsStream("/web-contract-fixtures.json")!!.bufferedReader().use { json.read(it.readText()) }
    private val validation=PurchaseValidation(json)
    private fun comparable(value: Any?): Any? = when(value) {
        is Map<*,*> -> value.mapValues { comparable(it.value) }
        is List<*> -> value.map { comparable(it) }
        is Number -> value.toDouble()
        else -> value
    }
    @Test fun `purchase normalization and duplicate keys match the existing web implementation`() {
        val bond=validation.bond(fixture["bond"].record());val share=validation.equity(fixture["share"].record())
        assertEquals(comparable(fixture["expectedBond"]),comparable(bond))
        assertEquals(comparable(fixture["expectedShare"]),comparable(share))
        assertEquals(fixture["bondKey"],validation.bondKey(bond));assertEquals(fixture["equityKey"],validation.equityKey(share))
    }
    @Test fun `compact overview preserves historical totals positions and monthly targets`() {
        val actual=MobileOverview.create(fixture.records("bonds"),fixture.records("shares"),LocalDate.parse("2026-10-26"))
        assertEquals(comparable(fixture["overview"]),comparable(actual))
        assertEquals("2023-02-28",MobileOverview.create(emptyList(),emptyList(),LocalDate.parse("2024-02-29"))["from"])
    }
    @Test fun `existing Node and browser password hashes authenticate without resets`() {
        val password=fixture.text("password")
        for(hash in fixture.strings("hashes")) {
            assertTrue(Passwords.verify(password,hash));assertFalse(Passwords.verify("wrong",hash))
        }
        val dollar=fixture.strings("hashes").first().replace(':','$')
        assertTrue(Passwords.verify(password,dollar))
        val modern=Passwords.hash(password);assertTrue(Passwords.verify(password,modern));assertFalse(Passwords.verify("wrong",modern))
    }
}
