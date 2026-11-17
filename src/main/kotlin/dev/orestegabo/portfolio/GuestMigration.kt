package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

/** Normal authentication plus per-request account binding; no anonymous transfer identity. */
@RestController
@RequestMapping("/api/bonds/guest-migration")
class GuestMigrationController(private val migration: GuestMigrationService) {
    @GetMapping("/status") fun status(@RequestParam accountId: String): Record {
        val account=currentAccount(); checkApi(account.id==accountId,"The signed-in account changed. Review the transfer again.",403)
        return migration.status(account.id)
    }
    @PostMapping("/items") fun item(@RequestBody body: Record)=migration.item(currentAccount().id,body)
    @PostMapping("/documents") fun document(@RequestBody body: Record)=migration.document(currentAccount().id,body)
}

@Service
class GuestMigrationService(private val db: JdbcTemplate,private val json: JsonCodec,private val portfolio: PortfolioService,
    private val validation: PurchaseValidation,private val documents: DocumentService,private val ledger: InvestmentLedger) {
    private fun bind(owner: String,p: Record,keys: Set<String>) {
        checkApi(p.keys==keys && p["confirmed"]==true,"Review and confirm the selected guest data.")
        checkApi(p.text("accountId")==owner,"The signed-in account changed. Review the transfer again.",403)
        checkApi(Regex("guest:(bond|share|receipt|document|cash):[A-Za-z0-9-]{1,80}").matches(p.text("sourceId")),"Only personal guest records can be transferred.")
        lockAccount(db,owner)
    }
    private fun prior(owner: String,source: String,payload: Record): Record? = db.queryForList(
        "SELECT result,payload=?::jsonb AS same FROM guest_migration_items WHERE owner_id=? AND source_id=?",json.write(payload),owner,source).singleOrNull()?.let {
        checkApi(it["same"]==true,"This local record changed after transfer. Review the account record; nothing was replaced.",409)
        json.read(it["result"].toString())
    }
    private fun acknowledge(owner: String,source: String,kind: String,payload: Record,target: String,matched: Boolean): Record {
        val result=mapOf("sourceId" to source,"kind" to kind,"targetId" to target,"status" to if(matched)"matched" else "created")
        db.update("INSERT INTO guest_migration_items(owner_id,source_id,kind,payload,result) VALUES (?,?,?,?::jsonb,?::jsonb)",owner,source,kind,json.write(payload),json.write(result))
        return result
    }
    fun status(owner: String): Record = mapOf("accountId" to owner,"items" to db.queryForList(
        "SELECT source_id,kind,result,created_at,payload->>'documentDate' AS document_date FROM guest_migration_items WHERE owner_id=? ORDER BY created_at DESC LIMIT 10000",owner).map {
        json.read(it["result"].toString())+mapOf("at" to it["created_at"].toString(),"documentDate" to it["document_date"])
    })
    private fun normalized(kind: String,raw: Record): Record {
        val allowed=if(kind=="bond") setOf("instrumentType","issuer","currency","market","purchaseDate","settlementDate","bondName","isin","tenorYears","faceValue","pricePercent","accruedInterestPaid","feesPaid","couponRate","withholdingTaxRate","maturityDate","firstCouponDate","couponDates","couponFrequency","scheduleConfidence","broker","accountReference","sourceUrl","status","notes")
            else setOf("instrumentType","exchange","currency","status","securityName","ticker","isin","tradeDate","settlementDate","shares","pricePerShare","grossConsideration","brokerageCommission","rseTransactionFee","csdTransactionLevy","cmaTransactionFee","totalCharges","netAmountPayable","broker","contractNote","cdsAccountNo","cdsReference","tradeReportId","notes")
        checkApi(raw.keys.all { it in allowed },"Invalid guest record fields or references.")
        checkApi(raw.text("isin").isNotBlank(),"Add the instrument ISIN in Guest Mode before transferring this investment.")
        if(kind=="equity") checkApi(raw.text("status","active")=="active","Review this share lot's status before transferring it.",409)
        return if(kind=="bond") validation.bond(raw) else validation.equity(raw)+mapOf("notes" to raw.text("notes").trim().also { checkApi(it.length<=1000,"Notes are too long.") })
    }
    private fun equalFinancial(a: Record,b: Record): Boolean = (a.keys+b.keys).filterNot { it in setOf("notes","sourceUrl") }.all { key ->
        val x=a[key];val y=b[key]
        if(x is Number && y is Number) x.toString().toBigDecimal().compareTo(y.toString().toBigDecimal())==0 else x==y
    }
    @Transactional fun item(owner: String,p: Record): Record {
        bind(owner,p,setOf("accountId","sourceId","kind","record","documentIds","confirmed"))
        val kind=p.text("kind");val source=p.text("sourceId");val raw=p["record"].record()
        checkApi(kind in listOf("bond","equity","receipt","cash") && source.startsWith("guest:${if(kind=="equity")"share" else kind}:"),"Invalid transfer kind.")
        val ids=p.strings("documentIds")
        checkApi(p["documentIds"] is List<*> && (p["documentIds"] as List<*>).size==ids.size && ids.size<=20 && ids.distinct().size==ids.size,"Invalid selected PDFs.")
        if(kind=="receipt") { checkApi(ids.isEmpty(),"Receipts do not accept PDF references."); return receipt(owner,source,raw) }
        if(kind=="cash") {
            checkApi(ids.isEmpty() && raw.keys.all { it in setOf("kind","date","currency","amount","shares","price","fees","tax","note","parentSourceId") },"Invalid cash transfer fields.")
            val payload=mapOf("kind" to "cash","record" to raw)
            prior(owner,source,payload)?.let { old ->
                checkApi(ledger.entries(owner).any { it.text("id")==old.text("targetId") },"The transferred transaction was removed. Review it.",409)
                return old+mapOf("status" to "already-transferred")
            }
            var record=raw.filterKeys { it!="parentSourceId" }+mapOf("id" to newId())
            if(raw.text("kind") in listOf("sale","dividend")) {
                val parent=db.queryForList("SELECT result FROM guest_migration_items WHERE owner_id=? AND source_id=? AND kind='equity'",owner,raw.text("parentSourceId")).singleOrNull()
                    ?: throw ApiError(409,"Transfer this transaction's share purchase first.")
                record=record+mapOf("holdingId" to json.read(parent["result"].toString()).text("targetId"))
            }
            val saved=ledger.save(owner,record)
            return acknowledge(owner,source,"cash",payload,saved.text("id"),false)
        }
        val data=normalized(kind,raw)
        val category=if(kind=="bond")"Bond confirmation" else "Share contract"
        val canonical=ids.map { documents.ownedEvidenceId(owner,it,category) ?: throw ApiError(400,"Selected private PDF was not found in this account.") }
        val payload=mapOf("kind" to kind,"record" to data,"documentIds" to canonical)
        prior(owner,source,payload)?.let { old ->
            val current=if(kind=="bond")portfolio.bond(owner,old.text("targetId")) else portfolio.shares(owner).singleOrNull { it.text("id")==old.text("targetId") }
            checkApi(current!=null && equalFinancial((if(kind=="bond")validation.bond(current) else validation.equity(current)),data),"The transferred account record was changed or removed. Review it; nothing was recreated.",409)
            return old+mapOf("status" to "already-transferred")
        }
        val candidates=if(kind=="bond")portfolio.bonds(owner).filter { validation.bondKey(it)==validation.bondKey(data) }
            else portfolio.shares(owner).filter { validation.equityKey(it)==validation.equityKey(data) }
        checkApi(candidates.size<=1,"Multiple existing investments require review.",409)
        val old=candidates.singleOrNull()
        if(old!=null) checkApi(equalFinancial(if(kind=="bond")validation.bond(old) else validation.equity(old),data),"An existing investment has different terms or account context. Review it; nothing was replaced.",409)
        val saved=old ?: if(kind=="bond")portfolio.saveBond(owner,data,contributeCouponTerms=false) else portfolio.saveShare(owner,data,preserveNotes=true)
        checkApi(equalFinancial(if(kind=="bond")validation.bond(saved) else validation.equity(saved),data),"Account normalization differs from the selected figures. Review the investment.",409)
        val target=saved.text("id")
        canonical.firstOrNull()?.let { proof ->
            val key=if(kind=="bond")"confirmationDocumentId" else "contractDocumentId"
            val existing=saved.text(key)
            checkApi(existing.isBlank() || documents.ownedEvidenceId(owner,existing,category)==proof,"The account investment has another original PDF. Review or transfer without attaching PDFs.",409)
            if(kind=="bond") db.update("UPDATE bond_purchases SET confirmation_document_id=?::uuid,confirmation_method='document',updated_at=now() WHERE owner_id=? AND id=?::uuid",proof,owner,target)
            else db.update("UPDATE equity_purchases SET holding=jsonb_set(holding,'{contractDocumentId}',to_jsonb(?::text)) WHERE owner_id=? AND id=?::uuid",proof,owner,target)
        }
        return acknowledge(owner,source,kind,payload,target,old!=null)
    }
    private fun receipt(owner: String,source: String,raw: Record): Record {
        checkApi(raw.keys==setOf("parentSourceId","dueDate","amount","received","method","currency","recordedAt") && raw["received"] is Boolean && raw.text("method") in listOf("manual","automatic"),"Invalid coupon receipt.")
        checkApi(raw.text("parentSourceId").startsWith("guest:bond:") && runCatching { Instant.parse(raw.text("recordedAt")) }.isSuccess,"Invalid receipt source.")
        val parent=db.queryForList("SELECT result FROM guest_migration_items WHERE owner_id=? AND source_id=? AND kind='bond'",owner,raw["parentSourceId"]).singleOrNull()
            ?: throw ApiError(409,"Select and transfer the receipt's bond first.")
        val target=json.read(parent["result"].toString()).text("targetId")
        val bond=portfolio.bond(owner,target) ?: throw ApiError(409,"The receipt's account bond was removed.")
        val due=date(raw.text("dueDate"));val amount=raw.decimal("amount","-1")
        checkApi(due!=null && due<=notificationToday() && due in bondCouponSchedule(bond) && bond.text("status") in listOf("active","matured") && raw.text("currency")==bond.text("currency") && amount.signum()>=0 && amount.stripTrailingZeros().scale()<=0,"Review the receipt date, currency and recorded whole-unit amount.",409)
        val payload=mapOf("kind" to "receipt","record" to raw)
        val existing=db.queryForList("SELECT amount,received,method FROM coupon_receipts WHERE owner_id=? AND purchase_id=?::uuid AND due_date=?::date",owner,target,raw["dueDate"]).singleOrNull()
        if(existing!=null) checkApi(existing.decimal("amount").compareTo(amount)==0 && existing["received"]==raw["received"] && existing["method"]==raw["method"],"The account receipt differs. Review it; nothing was replaced.",409)
        prior(owner,source,payload)?.let { checkApi(existing!=null,"The transferred receipt was removed. Review it.",409); return it+mapOf("status" to "already-transferred") }
        if(existing==null) db.update("INSERT INTO coupon_receipts(owner_id,purchase_id,due_date,amount,received,method) VALUES (?,?::uuid,?::date,?,?,?)",owner,target,raw["dueDate"],amount,raw["received"],raw["method"])
        return acknowledge(owner,source,"receipt",payload,target,existing!=null)
    }
    @Transactional fun document(owner: String,p: Record): Record {
        bind(owner,p,setOf("accountId","sourceId","confirmed","sha256","metadata","uploadId","index","count","content"))
        checkApi(p.text("sourceId").startsWith("guest:document:") && Regex("[a-f0-9]{64}").matches(p.text("sha256")),"Invalid private document source.")
        val metadata=p["metadata"].record();val category=metadata.text("category")
        checkApi(metadata.keys==setOf("category","label","instrumentName","issuer","documentDate") && category in listOf("Bond confirmation","Share contract") && metadata.text("label")==category && metadata.text("instrumentName").isEmpty() && metadata.text("issuer").isEmpty() && date(metadata.text("documentDate"))!=null,"Select a private PDF type and verify its document date.")
        val payload=mapOf("kind" to "document","sha256" to p["sha256"],"category" to category,"documentDate" to metadata["documentDate"])
        prior(owner,p.text("sourceId"),payload)?.let { old ->
            checkApi(documents.ownedEvidenceId(owner,old.text("targetId"),category)!=null,"The transferred PDF was removed. Review it.",409)
            return old+mapOf("documentId" to old["targetId"],"status" to "already-transferred")
        }
        val result=documents.upload(owner,p.filterKeys { it in setOf("metadata","uploadId","index","count","content") })
        if(result.text("documentId").isBlank()) return emptyMap()
        val id=result.text("documentId")
        val digest=db.queryForObject("SELECT sha256 FROM bond_uploaded_documents WHERE owner_id=? AND id=?::uuid",String::class.java,owner,id)
        checkApi(digest==p.text("sha256"),"PDF integrity check failed.")
        checkApi(documents.ownedEvidenceId(owner,id,category)!=null,"This PDF is stored with a different document type. Review it.",409)
        return acknowledge(owner,p.text("sourceId"),"document",payload,id,false)+mapOf("documentId" to id)
    }
}
