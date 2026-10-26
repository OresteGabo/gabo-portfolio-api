package dev.orestegabo.portfolio

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.JdbcTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/** Explicit, one-time local seed import. Never reads .env or connects to the website database. */
@Component
class WebSeedImport(private val importer: WebSeedImporter,@Value("\${gabo.import-web-directory:}") private val directory: String): ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if(directory.isNotBlank()) importer.importDirectory(Path.of(directory))
    }
}

@Service
class WebSeedImporter(private val db: JdbcTemplate,private val json: JsonCodec,private val portfolio: PortfolioService,private val validation: PurchaseValidation) {
    private val log=LoggerFactory.getLogger(javaClass)
    @Transactional fun importDirectory(root: Path) {
        db.query("SELECT pg_advisory_xact_lock(hashtextextended('spring-web-seed-v1',0))",{ _ -> })
        if(db.queryForList("SELECT key FROM bond_data_migrations WHERE key='spring-web-seed-v1'").isNotEmpty()) { log.info("Website seed was already imported; skipping."); return }
        val base=root.toRealPath()
        fun records(file: String,key: String?=null): List<Record> {
            val text=Files.readString(base.resolve(file))
            return if(key==null) json.list(text) else json.read(text).records(key)
        }
        val bonds=records(".data/bonds-portfolio.json","purchases")
        val shares=records(".data/equity-portfolio.json","holdings")
        var bondCount=0; var shareCount=0; var documentCount=0
        for(raw in bonds) {
            val defaults=mapOf("instrumentType" to "treasury","issuer" to "Government of Rwanda","currency" to "RWF","market" to "primary","pricePercent" to 100,"accruedInterestPaid" to 0,"feesPaid" to 0,"couponFrequency" to 2,"scheduleConfidence" to "estimated")
            val p=validation.canonical(defaults+raw+mapOf("settlementDate" to raw.text("settlementDate",raw.text("purchaseDate")),"faceValue" to (raw["faceValue"] ?: raw["amountInvested"])))
            checkApi(uuid(raw.text("id")),"The seed contains an invalid purchase identifier.")
            if(db.queryForList("SELECT id FROM bond_purchases WHERE id=?::uuid OR (owner_id='owner' AND dedup_key=?)",raw["id"],validation.bondKey(p)).isNotEmpty())continue
            val saved=portfolio.saveBond("owner",p)
            db.update("UPDATE bond_purchases SET id=?::uuid,created_at=?::timestamptz WHERE id=?::uuid AND owner_id='owner'",raw["id"],validCreated(raw),saved["id"])
            bondCount++
        }
        for(raw in shares) {
            val legacyId=raw.text("id")
            checkApi(legacyId.isNotBlank() && legacyId.length<=160,"The seed contains an invalid share identifier.")
            // Bundled web share lots predate UUID-backed storage. Keep the public lot ID
            // inside holding JSON and assign a stable UUID only to the database row.
            val databaseId=if(uuid(legacyId))legacyId else java.util.UUID.nameUUIDFromBytes(("gabo-web-share:"+legacyId).toByteArray(Charsets.UTF_8)).toString()
            val contract=raw.text("contractNote").takeIf { it.isNotBlank() }?.let { "${normalized(raw.text("broker"))}|${normalized(it)}" }
            if(db.queryForList("SELECT id FROM equity_purchases WHERE id=?::uuid OR (owner_id='owner' AND (holding->>'id'=? OR dedup_key=? OR contract_key=? OR report_key=?))",databaseId,legacyId,validation.equityKey(raw),contract,raw.text("tradeReportId").ifBlank { null }).isNotEmpty())continue
            val saved=portfolio.saveShare("owner",raw)
            val holding=saved+mapOf("id" to raw["id"],"createdAt" to validCreated(raw),"status" to raw.text("status","active"),"notes" to raw.text("notes"))
            db.update("UPDATE equity_purchases SET id=?::uuid,created_at=?::timestamptz,holding=?::jsonb WHERE id=?::uuid AND owner_id='owner'",databaseId,holding["createdAt"],json.write(holding),saved["id"])
            shareCount++
        }
        val manifest=base.resolve("private/bonds/bond-documents.json")
        if(Files.exists(manifest)) for(m in records("private/bonds/bond-documents.json")) {
            val name=m.text("fileName")
            checkApi(Regex("[a-zA-Z0-9._-]+").matches(name) && Regex("[a-zA-Z0-9_-]+").matches(m.text("id")),"Invalid private document path.")
            val documentsRoot=base.resolve("private/bonds/documents").toRealPath()
            val path=documentsRoot.resolve(name).toRealPath()
            checkApi(path.startsWith(documentsRoot) && Files.size(path)<=10*1024*1024,"Invalid private document file.")
            val bytes=Files.readAllBytes(path)
            checkApi(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray()),"A private document is not a PDF.")
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val metadata=m.filterKeys { it in listOf("label","documentDate","category","instrumentName","issuer","description","details","originalFileName") }
            documentCount+=db.update("INSERT INTO bond_uploaded_documents(owner_id,sha256,content,metadata,legacy_id) VALUES('owner',?,?,?::jsonb,?) ON CONFLICT DO NOTHING",sha,Base64.getEncoder().encodeToString(bytes),json.write(metadata),m["id"])
        }
        db.update("INSERT INTO bond_data_migrations(key) VALUES ('spring-web-seed-v1')")
        log.info("Website seed imported: {} bonds, {} share purchases, {} documents.",bondCount,shareCount,documentCount)
    }
    private fun validCreated(p: Record)=runCatching { Instant.parse(p.text("createdAt")).toString() }.getOrDefault(Instant.now().toString())
}
