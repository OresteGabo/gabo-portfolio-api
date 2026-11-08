package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.Base64

@Service
class BondApplicationForms(private val db: JdbcTemplate, private val json: JsonCodec) {
    private val calendar = javaClass.getResourceAsStream("/issuance-calendar.json")!!
        .bufferedReader().use { json.list(it.readText()) }

    /** Bind uploads to the server's published auction, ignoring client-supplied dates and roles. */
    fun metadata(calendarId: String): Record {
        val issue = calendar.singleOrNull { it.text("id") == calendarId }
            ?: throw ApiError(400, "Choose a published calendar issuance for this application form.")
        val application = issue.filterKeys { it in setOf("title", "tenorYears", "settlementDate", "maturityDate") } +
            mapOf("calendarId" to calendarId, "issuanceDate" to issue.text("closingBookDate"))
        return mapOf("category" to "Bond application", "visibility" to "shared",
            "label" to "Bond application form", "instrumentName" to issue.text("title"), "issuer" to "BNR",
            "documentDate" to issue.text("closingBookDate"), "description" to "Blank public application form",
            "originalFileName" to "bond-application-${issue.text("closingBookDate")}.pdf",
            "applicationForm" to application)
    }

    private fun key(metadata: Record): String = metadata["applicationForm"].record().let {
        "${it.text("settlementDate")}|${it.number("tenorYears").toInt()}|${it.text("maturityDate")}"
    }

    fun documents(): List<Record> = db.query("SELECT id::text,metadata FROM bond_application_forms ORDER BY created_at DESC", { r, _ ->
        json.read(r.getString(2)) + mapOf("id" to r.getString(1), "downloadUrl" to "/api/bonds/documents/${r.getString(1)}")
    })

    fun download(id: String): Pair<ByteArray, String>? = db.query("SELECT content,metadata FROM bond_application_forms WHERE id::text=?", { r, _ ->
        Base64.getDecoder().decode(r.getString(1)) to json.read(r.getString(2)).text("originalFileName")
    }, id).firstOrNull()

    /** The database arbitrates retries/concurrent uploads; no per-user or duplicate PDF is created. */
    fun store(metadata: Record, hash: String, bytes: ByteArray): Record {
        val key = key(metadata)
        val inserted = db.queryForList("""INSERT INTO bond_application_forms(issue_key,sha256,content,metadata)
            VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING RETURNING id::text""",
            key, hash, Base64.getEncoder().encodeToString(bytes), json.write(metadata)).singleOrNull()
        if (inserted != null) return mapOf("documentId" to inserted.text("id"), "duplicate" to false)
        val existing = db.queryForList("SELECT id::text,sha256 FROM bond_application_forms WHERE issue_key=?", key).singleOrNull()
        checkApi(existing != null && existing.text("sha256") == hash,
            "A different application form is already saved for this issuance, or this PDF belongs to another issuance.", 409)
        return mapOf("documentId" to existing!!.text("id"), "duplicate" to true)
    }
}
