package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64

@Service
class DocumentService(private val db: JdbcTemplate,private val json: JsonCodec,private val pending: PendingIssuanceService) {
    fun documents(owner: String): List<Record> = db.query("SELECT COALESCE(legacy_id,id::text),metadata FROM bond_uploaded_documents WHERE owner_id=? ORDER BY created_at DESC",{r,_->json.read(r.getString(2))+mapOf("id" to r.getString(1),"downloadUrl" to "/api/bonds/documents/${r.getString(1)}")},owner) + db.query("SELECT id::text,metadata FROM bond_issuance_documents ORDER BY created_at DESC",{r,_->json.read(r.getString(2))+mapOf("id" to r.getString(1),"downloadUrl" to "/api/bonds/documents/${r.getString(1)}")})
    fun download(owner: String,id: String): Pair<ByteArray,String>? {
        if(!Regex("[a-zA-Z0-9_-]{1,160}").matches(id)) return null
        return db.query("SELECT content,metadata FROM bond_uploaded_documents WHERE owner_id=? AND (id::text=? OR legacy_id=?)",{r,_->Base64.getDecoder().decode(r.getString(1)) to json.read(r.getString(2)).text("originalFileName","document.pdf")},owner,id,id).firstOrNull() ?: db.query("SELECT content FROM bond_issuance_documents WHERE id::text=?",{r,_->Base64.getDecoder().decode(r.getString(1)) to "bond-issuance.pdf"},id).firstOrNull()
    }
    @Transactional fun upload(owner: String,p: Record): Record {
        lockAccount(db,owner)
        val error="Invalid PDF upload. Choose a PDF of up to 10 MB."
        val count=p.number("count"); val index=p.number("index")
        checkApi(Regex("[a-f0-9]{32}").matches(p.text("uploadId")) && count in 1.0..20.0 && count%1==0.0 && index>=0 && index<count && index%1==0.0 && p.text("content").length<=699052,error)
        val bytes=runCatching { Base64.getDecoder().decode(p.text("content")) }.getOrElse { throw ApiError(400,error) }
        checkApi(bytes.isNotEmpty() && bytes.size<=512*1024 && Base64.getEncoder().encodeToString(bytes)==p.text("content") && (index==count-1 || bytes.size==512*1024) && (index!=0.0 || bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())),error)
        val m=p["metadata"].record()
        checkApi(m.text("category") in listOf("Bond confirmation","Share contract","Bond issuance","Statement") && date(m.text("documentDate"))!=null && listOf("label","instrumentName","issuer").all { m[it] is String && m.text(it).length<=240 },error)
        val issuance=if(m.text("category")=="Bond issuance") BondIssuance.validate(m["issuance"].record()) else null
        val metadata=m.filterKeys { it in listOf("label","instrumentName","issuer","category","documentDate") }+mapOf("description" to "","originalFileName" to if(m.text("category")=="Bond confirmation") "bond-confirmation.pdf" else if(issuance!=null) "bond-issuance.pdf" else if(m.text("category")=="Statement") "broker-statement.pdf" else "share-contract.pdf") + (issuance?.let { mapOf("issuance" to it,"issuanceKey" to BondIssuance.key(it),"details" to BondIssuance.details(it)) } ?: emptyMap())
        db.update("DELETE FROM bond_document_uploads WHERE owner_id=? AND created_at<now()-interval '1 day'",owner)
        // JSONB compares equivalent numeric forms (e.g. 1E10 and 10000000000) across upload parts.
        val existing=db.queryForList("SELECT parts,part_count,metadata=?::jsonb AS same_metadata FROM bond_document_uploads WHERE owner_id=? AND upload_id=? FOR UPDATE",json.write(metadata),owner,p["uploadId"]).firstOrNull()
        val parts=if(existing==null) linkedMapOf<String,Any?>() else json.read(existing["parts"].toString()).toMutableMap()
        if(existing!=null) checkApi((existing["part_count"] as Number).toInt()==count.toInt() && existing["same_metadata"]==true,"This upload could not be resumed.",409)
        else checkApi((db.queryForObject("SELECT count(*) FROM bond_document_uploads WHERE owner_id=?",Long::class.java,owner) ?: 0)<8,"Too many unfinished uploads. Please try again later.",429)
        val key=index.toInt().toString()
        checkApi(parts[key]==null || parts[key]==p["content"],"This upload chunk conflicts with an earlier selection.",409)
        parts[key]=p["content"]
        db.update("INSERT INTO bond_document_uploads(owner_id,upload_id,part_count,parts,metadata) VALUES (?,?,?,?::jsonb,?::jsonb) ON CONFLICT(owner_id,upload_id) DO UPDATE SET parts=EXCLUDED.parts",owner,p["uploadId"],count.toInt(),json.write(parts),json.write(metadata))
        if(parts.size!=count.toInt()) return emptyMap()
        val complete=(0 until count.toInt()).map { Base64.getDecoder().decode(parts[it.toString()].toString()) }.fold(java.io.ByteArrayOutputStream()) { out,part->out.apply { write(part) } }.toByteArray()
        checkApi(complete.size<=10*1024*1024 && complete.take(5).toByteArray().contentEquals("%PDF-".toByteArray()),error)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(complete).joinToString("") { "%02x".format(it) }
        if(issuance!=null) {
            // Global unique identity resolves simultaneous uploads from different accounts.
            // A conflicting report never overwrites the canonical terms or source PDF.
            val saved=db.queryForList("INSERT INTO bond_issuance_documents(issue_key,sha256,content,metadata) VALUES (?,?,?,?::jsonb) ON CONFLICT(issue_key) DO UPDATE SET issue_key=EXCLUDED.issue_key RETURNING id::text,metadata,(xmax<>0) AS duplicate",BondIssuance.key(issuance),hash,Base64.getEncoder().encodeToString(complete),json.write(metadata)).single()
            val prior=BondIssuance.validate(json.read(saved["metadata"].toString())["issuance"].record())
            checkApi(prior==issuance,"This bond issuance is already saved with different figures. Review the existing report.",409)
            db.update("DELETE FROM bond_document_uploads WHERE owner_id=? AND upload_id=?",owner,p["uploadId"])
            val updated=pending.refresh()
            return mapOf("documentId" to saved["id"],"duplicate" to saved["duplicate"],"updatedPendingPurchases" to updated)
        }
        val id=db.queryForObject("INSERT INTO bond_uploaded_documents(owner_id,sha256,content,metadata) VALUES (?,?,?,?::jsonb) ON CONFLICT(owner_id,sha256) DO UPDATE SET sha256=EXCLUDED.sha256 RETURNING id::text",String::class.java,owner,hash,Base64.getEncoder().encodeToString(complete),json.write(metadata))!!
        db.update("DELETE FROM bond_document_uploads WHERE owner_id=? AND upload_id=?",owner,p["uploadId"])
        return mapOf("documentId" to id)
    }
}
