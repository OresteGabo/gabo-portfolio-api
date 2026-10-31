package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

/** Existing statement matcher, now separating identity evidence from amount/date candidates. */
internal object StatementMatching {
    private fun near(a: BigDecimal,b: BigDecimal)=(a-b).abs()<=BigDecimal("0.02")
    private fun eligible(item: Record,p: Record)=item.text("kind") in listOf("bond","equity") && p.text("currency")==item.text("currency") && p.text("status") in listOf("active","matured","sold") &&
        (item.text("kind")=="equity")== (p.text("instrumentType")=="equity")
    private fun securityId(value: String)=normalized(value.uppercase().removeSuffix("/MN"))
    private fun bondIdentity(item: Record,p: Record): Boolean =
        item.text("isin").isNotBlank() && securityId(item.text("isin"))==securityId(p.text("isin")) ||
        item.text("transactionReference").isNotBlank() && normalized(item.text("transactionReference"))==normalized(p.text("transactionReference"))
    fun matches(item: Record,p: Record): Boolean {
        if(!eligible(item,p) || normalized(p.text("broker"))!="BKCAPITAL")return false
        if(item.text("kind")=="equity")return listOf("contractNote","ticker").all { item.text(it).isNotBlank() && p.text(it)==item.text(it) } &&
            p.text("tradeDate")==item.text("date") && p.decimal("shares").compareTo(item.decimal("shares"))==0 && near(p.decimal("pricePerShare"),item.decimal("pricePerShare")) && near(p.decimal("netAmountPayable"),item.decimal("amount"))
        return (item.text("isin").isBlank() || securityId(item.text("isin"))==securityId(p.text("isin"))) && bondIdentity(item,p) && bondAmountAndDate(item,p)
    }
    private fun bondAmountAndDate(item: Record,p: Record): Boolean {
        val days=ChronoUnit.DAYS.between(date(p.text("settlementDate")) ?: return false,date(item.text("date")) ?: return false)
        return days in 0..7 && near(p.decimal("faceValue")*p.decimal("pricePercent")/BigDecimal(100)+p.decimal("accruedInterestPaid"),item.decimal("amount"))
    }
    fun candidate(item: Record,p: Record): Boolean {
        if(!eligible(item,p))return false
        if(p.text("broker").isNotBlank() && normalized(p.text("broker"))!="BKCAPITAL")return false
        if(item.text("isin").isNotBlank() && securityId(item.text("isin"))!=securityId(p.text("isin")))return false
        return if(item.text("kind")=="equity")item.text("contractNote").isNotBlank() && p.text("contractNote")==item.text("contractNote") && p.text("ticker")==item.text("ticker")
        else bondIdentity(item,p) || bondAmountAndDate(item,p)
    }
    fun canConfirm(item: Record,p: Record): Boolean {
        if(!candidate(item,p))return false
        return if(item.text("kind")=="equity")p.decimal("shares").compareTo(item.decimal("shares"))==0 && near(p.decimal("pricePerShare"),item.decimal("pricePerShare")) && near(p.decimal("netAmountPayable"),item.decimal("amount"))
        else near(p.decimal("faceValue")*p.decimal("pricePercent")/BigDecimal(100)+p.decimal("accruedInterestPaid"),item.decimal("amount"))
    }
    fun fingerprint(p: Record): String = Passwords.sha((listOf("instrumentType","currency","broker","isin","ticker","contractNote","tradeDate","purchaseDate","settlementDate","status").map { p.text(it) } +
        listOf("faceValue","pricePercent","accruedInterestPaid","shares","pricePerShare","netAmountPayable").map { p.decimal(it).stripTrailingZeros().toPlainString() }).joinToString("\u0000"))
    fun status(item: Record,purchases: List<Record>): String = when {
        item.text("kind") !in listOf("bond","equity") -> "evidence-only"
        purchases.count { matches(item,it) }==1 -> "matched"
        purchases.any { candidate(item,it) } -> "review-required"
        else -> "unmatched"
    }
}

@Service
class StatementService(private val db: JdbcTemplate,private val json: JsonCodec,private val portfolio: PortfolioService,private val instruments: BondInstrumentService,private val validation: PurchaseValidation) {
    private fun validate(raw: Record): Record {
        val error="Could not verify this BK Capital statement."
        val kinds=listOf("bond","equity","cash-receipt","fee","coupon","dividend","other")
        checkApi(raw.text("kind") in kinds && date(raw.text("date"))!=null && raw.text("currency")=="RWF" && raw.text("broker")=="BK Capital" && raw["amount"] is Number && raw.decimal("amount")>BigDecimal.ZERO && raw.decimal("amount")<=BigDecimal("10000000000000") && raw.decimal("amount").stripTrailingZeros().scale()<=2 && raw.text("title").isNotBlank() && raw.text("title").length<=160,error)
        val p=raw.filterKeys { it in listOf("kind","date","amount","title","currency","broker","direction","rowNumber") }.toMutableMap()
        p["amount"]=raw.decimal("amount").stripTrailingZeros()
        for(key in listOf("isin","transactionReference")) {
            checkApi(raw[key]==null || raw[key] is String && raw.text(key).length<=120,error)
            if(raw.text(key).isNotBlank())p[key]=raw.text(key).trim()
        }
        if(raw.text("kind")=="equity") {
            checkApi(Regex("[A-Z][A-Z0-9]{1,9}").matches(raw.text("ticker")) && Regex("\\d{1,20}#\\d{1,8}").matches(raw.text("contractNote")) && raw["shares"] is Number && raw.decimal("shares")>BigDecimal.ZERO && raw.decimal("shares")<=BigDecimal("10000000000000") && raw.decimal("shares").stripTrailingZeros().scale()<=0 && raw["pricePerShare"] is Number && raw.decimal("pricePerShare")>BigDecimal.ZERO && raw.decimal("pricePerShare")<=BigDecimal("10000000000000") && raw.decimal("amount")+BigDecimal("0.02")>=raw.decimal("shares")*raw.decimal("pricePerShare"),error)
            for(k in listOf("ticker","contractNote"))p[k]=raw[k]
            for(k in listOf("shares","pricePerShare"))p[k]=raw.decimal(k).stripTrailingZeros()
        }
        if(raw.containsKey("rowNumber"))checkApi(raw["rowNumber"] is Number && raw.number("rowNumber")%1==0.0 && raw.number("rowNumber") in 1.0..500.0,error)
        if(raw.text("kind") !in listOf("bond","equity"))checkApi(raw.text("direction") in listOf("debit","credit"),error)
        return p
    }
    fun matches(item: Record,p: Record)=StatementMatching.matches(item,p)
    private fun ownedDocument(owner: String,id: String): Record {
        checkApi(uuid(id),"Invalid source document.")
        return db.query("SELECT metadata FROM bond_uploaded_documents WHERE owner_id=? AND id=?::uuid",{r,_->json.read(r.getString(1))},owner,id).firstOrNull() ?: throw ApiError(404,"Source document not found.")
    }
    private fun rawItems(owner: String,documentId: String?): List<Record> {
        if(documentId!=null) {
            ownedDocument(owner,documentId)
            return db.query("SELECT i.id::text,i.item FROM bond_statement_items i JOIN bond_statement_sources s ON s.item_id=i.id AND s.owner_id=i.owner_id WHERE i.owner_id=? AND s.document_id=?::uuid ORDER BY i.created_at,i.id",{r,_->json.read(r.getString(2))+mapOf("id" to r.getString(1))},owner,documentId)
        }
        return db.query("SELECT id::text,item FROM bond_statement_items WHERE owner_id=? ORDER BY created_at DESC LIMIT 500",{r,_->json.read(r.getString(2))+mapOf("id" to r.getString(1))},owner)
    }
    private fun decorate(owner: String,item: Record,purchases: List<Record>, evidence: Pair<Map<String,List<Record>>,Map<String,List<Record>>>,available: List<Record> = instruments.instruments()): Record {
        val decisions=evidence.second[item.text("id")].orEmpty()
        val latest=decisions.lastOrNull()
        val manual=latest?.takeIf { it.text("action")=="link" }?.let { decision-> purchases.firstOrNull { it.text("id")==decision.text("purchaseId") && StatementMatching.candidate(item,it) && StatementMatching.fingerprint(it)==decision.text("purchaseFingerprint") } }
        // A clear is an explicit override: do not immediately re-apply an automatic match.
        val status=when {
            manual!=null -> "matched"
            latest?.text("action")=="clear" && item.text("kind") in listOf("bond","equity") -> "review-required"
            latest?.text("action")=="link" && manual==null -> "review-required"
            else -> StatementMatching.status(item,purchases)
        }
        val strong=purchases.filter { matches(item,it) }
        val candidates=purchases.filter { StatementMatching.candidate(item,it) }
        val sources=evidence.first[item.text("id")].orEmpty()
        return item+mapOf("reconciliationStatus" to status,"state" to when(status) { "matched"->"recorded";"unmatched"->"missing";"review-required"->"review-required";else->"evidence" },
            "matchMethod" to if(manual!=null)"manual" else if(status=="matched")"identity" else "none",
            "purchaseId" to (manual?.get("id") ?: strong.singleOrNull()?.get("id")?.takeIf { status=="matched" } ?: ""),
            "candidates" to candidates.map { it.filterKeys { key->key in listOf("id","bondName","securityName","ticker","isin","purchaseDate","settlementDate","tradeDate","faceValue","pricePercent","shares","netAmountPayable","currency","broker","instrumentType","confirmationDocumentId","contractDocumentId") } + mapOf("canLink" to StatementMatching.canConfirm(item,it)) },
            "sources" to sources,"decisions" to decisions,"bondOptions" to if(status=="unmatched" && sources.isNotEmpty())instruments.options(item,available) else emptyList<Record>())
    }
    private fun evidence(owner: String,items: List<Record>): Pair<Map<String,List<Record>>,Map<String,List<Record>>> {
        if(items.isEmpty())return emptyMap<String,List<Record>>() to emptyMap()
        val ids=items.map { it.text("id") };val slots=ids.joinToString(",") { "?::uuid" };val args=(listOf(owner)+ids).toTypedArray()
        val sources=db.query("SELECT s.item_id::text,s.document_id::text,d.metadata,s.source_item FROM bond_statement_sources s JOIN bond_uploaded_documents d ON d.id=s.document_id AND d.owner_id=s.owner_id WHERE s.owner_id=? AND s.item_id IN ($slots) ORDER BY s.created_at",{r,_->mapOf("_itemId" to r.getString(1),"documentId" to r.getString(2),"label" to json.read(r.getString(3)).text("label"),"item" to json.read(r.getString(4)))},*args)
        val decisions=db.query("SELECT item_id::text,decision FROM bond_statement_decisions WHERE owner_id=? AND item_id IN ($slots) ORDER BY sequence",{r,_->json.read(r.getString(2))+mapOf("_itemId" to r.getString(1))},*args)
        fun grouped(rows: List<Record>)=rows.groupBy { it.text("_itemId") }.mapValues { (_,rows)->rows.map { it-"_itemId" } }
        return grouped(sources) to grouped(decisions)
    }
    fun records(owner: String,documentId: String?=null): List<Record> {
        val purchases=portfolio.bonds(owner)+portfolio.shares(owner)
        val items=rawItems(owner,documentId);val evidence=evidence(owner,items)
        val available=instruments.instruments()
        return items.map { decorate(owner,it,purchases,evidence,available) }
    }
    fun missing(owner: String)=records(owner).filter { it.text("reconciliationStatus") in listOf("unmatched","review-required") && it.text("kind") in listOf("bond","equity") }
    @Transactional fun reconcile(owner: String,raw: Record): List<Record> {
        lockAccount(db,owner)
        val input=raw["items"] as? List<*> ?: throw ApiError(400,"Could not verify this BK Capital statement.")
        checkApi(!raw.containsKey("movements") || raw["movements"] is List<*>,"Invalid statement movements.")
        checkApi(raw["documentId"]==null || raw["documentId"] is String,"Invalid statement source.")
        val movements=raw["movements"] as? List<*> ?: emptyList<Any?>()
        checkApi(input.size+movements.size in 1..500,"Could not verify this BK Capital statement.")
        val documentId=raw.text("documentId").takeIf(String::isNotBlank)
        if(documentId!=null)checkApi(ownedDocument(owner,documentId).text("category")=="Statement","Choose a statement source PDF.")
        val purchases=portfolio.bonds(owner)+portfolio.shares(owner)
        val saved=(input+movements).map { value->
            val item=validate(value.record())
            // Canonical decimal strings make 1000, 1000.0 and 1E3 the same identity.
            val identityValues=listOf(item["kind"],item["date"],item.decimal("amount").toPlainString(),item["ticker"] ?: "",item["contractNote"] ?: "",item.decimal("shares").toPlainString(),item.decimal("pricePerShare").toPlainString(),
                if(item.text("kind")!="equity")item["title"] else "",if(item.text("kind")!="equity" && item.text("isin").isBlank() && item.text("transactionReference").isBlank() && documentId!=null) "$documentId:${item["rowNumber"] ?: ""}" else "")
            val identity=Passwords.sha(json.write(identityValues))
            val comparison=item.filterKeys { it in listOf("kind","date","amount","title","currency","broker","ticker","contractNote","shares","pricePerShare") }
            val legacy=if(documentId==null)emptyList() else db.query("SELECT i.id::text FROM bond_statement_items i WHERE i.owner_id=? AND i.item @> ?::jsonb AND (NOT EXISTS(SELECT 1 FROM bond_statement_sources s WHERE s.item_id=i.id) OR EXISTS(SELECT 1 FROM bond_statement_sources s WHERE s.item_id=i.id AND s.owner_id=? AND s.document_id=?::uuid AND s.source_item @> ?::jsonb)) LIMIT 2",{r,_->r.getString(1)},owner,json.write(comparison),owner,documentId,json.write(item))
            val id=legacy.singleOrNull() ?: db.queryForObject("INSERT INTO bond_statement_items(owner_id,identity,item) VALUES (?,?,?::jsonb) ON CONFLICT(owner_id,identity) DO UPDATE SET identity=EXCLUDED.identity RETURNING id::text",String::class.java,owner,identity,json.write(item))!!
            if(documentId!=null)db.update("INSERT INTO bond_statement_sources(owner_id,item_id,document_id,source_item) VALUES (?,?::uuid,?::uuid,?::jsonb) ON CONFLICT(owner_id,item_id,document_id) DO NOTHING",owner,id,documentId,json.write(item))
            item+mapOf("id" to id)
        }
        val evidence=evidence(owner,saved)
        val available=instruments.instruments()
        return saved.map { decorate(owner,it,purchases,evidence,available) }
    }
    /** Explicit reviewed creation, never automatic inference from a month or statement debit. */
    @Transactional fun createBond(owner: String,id: String,raw: Record): List<Record> {
        lockAccount(db,owner);checkApi(uuid(id),"Invalid statement record.")
        val row=records(owner).firstOrNull { it.text("id")==id } ?: throw ApiError(404,"Statement record not found.")
        checkApi(row.text("kind")=="bond" && row.records("sources").isNotEmpty(),"Choose a saved bond statement entry.")
        checkApi(raw["confirmed"]==true,"Confirm the bond and your actual purchase figures.")
        val reason=raw.text("reason").trim();checkApi(reason.length in 3..500,"Explain this purchase confirmation.")
        val instrument=instruments.options(row).singleOrNull { it.text("id")==raw.text("instrumentId") }
            ?: throw ApiError(400,"Choose saved bond terms consistent with this statement date.")
        val actual=listOf("faceValue","pricePercent","accruedInterestPaid","feesPaid","withholdingTaxRate")
        checkApi(actual.all { raw[it] is Number },"Enter principal, executed price, accrued interest, fees and withholding tax explicitly.")
        val input=instrument.filterKeys { it !in listOf("id","issuanceDate") }+raw.filterKeys { it in actual }+
            mapOf("purchaseDate" to instrument.text("settlementDate"),"status" to "active","broker" to "BK Capital","notes" to "Purchase confirmed from statement; public bond terms reused.")
        val proposed=validation.bond(input)
        checkApi(actual.all { proposed.decimal(it).stripTrailingZeros().scale() <= if(it in listOf("pricePercent","withholdingTaxRate"))6 else 2 },"Use decimal money amounts with at most two fractional digits.")
        checkApi((proposed.decimal("faceValue")*proposed.decimal("pricePercent")/java.math.BigDecimal(100)+proposed.decimal("accruedInterestPaid")-row.decimal("amount")).abs()<=java.math.BigDecimal("0.02"),
            "Principal and execution figures must equal the statement bond cost; separately charged fees are additional.",409)
        val prior=row.records("decisions").lastOrNull()
        val purchases=portfolio.bonds(owner)
        val existing=purchases.firstOrNull { it.text("id")==prior?.text("purchaseId") }
        if(prior?.text("origin")=="statement-purchase" && existing!=null && prior.text("instrumentId")==instrument.text("id")) {
            checkApi(StatementMatching.fingerprint(existing)==StatementMatching.fingerprint(proposed) && actual.all { existing.decimal(it).compareTo(proposed.decimal(it))==0 },"This statement entry already created a different purchase. Review its existing link.",409)
            return listOf(row)
        }
        checkApi(row.text("reconciliationStatus")=="unmatched" && purchases.none {
            val sameSecurity=it.text("isin").isNotBlank() && normalized(it.text("isin").uppercase().removeSuffix("/MN"))==normalized(instrument.text("isin").uppercase().removeSuffix("/MN"))
            val sameUnidentifiedIssue=it.text("isin").isBlank() && (normalized(it.text("bondName"))==normalized(instrument.text("bondName")) ||
                it.decimal("tenorYears").compareTo(instrument.decimal("tenorYears"))==0 && it.text("maturityDate")==instrument.text("maturityDate"))
            StatementMatching.candidate(row,it) || (sameSecurity || sameUnidentifiedIssue) && it.text("settlementDate")==instrument.text("settlementDate")
        },"An existing or pending purchase may describe this entry. Review and link it instead of creating another.",409)
        checkApi(raw.text("expectedDecisionAt")==prior?.text("at").orEmpty(),"This reconciliation changed. Refresh before confirming.",409)
        val purchase=portfolio.saveBond(owner,proposed)
        db.update("UPDATE bond_purchases SET statement_document_id=?::uuid WHERE owner_id=? AND id=?::uuid",row.records("sources").first().text("documentId"),owner,purchase.text("id"))
        val decision=mapOf("action" to "link","origin" to "statement-purchase","instrumentId" to instrument.text("id"),"purchaseId" to purchase.text("id"),"reason" to reason,
            "actor" to owner,"at" to java.time.Instant.now().toString(),"purchaseFingerprint" to StatementMatching.fingerprint(purchase))
        db.update("INSERT INTO bond_statement_decisions(owner_id,item_id,decision) VALUES (?,?::uuid,?::jsonb)",owner,id,json.write(decision))
        return records(owner).filter { it.text("id")==id }
    }
    @Transactional fun resolve(owner: String,id: String,raw: Record): List<Record> {
        lockAccount(db,owner);checkApi(uuid(id),"Invalid statement record.")
        val item=db.query("SELECT item FROM bond_statement_items WHERE owner_id=? AND id=?::uuid",{r,_->json.read(r.getString(1))},owner,id).firstOrNull() ?: throw ApiError(404,"Statement record not found.")
        val action=raw.text("action");val reason=raw.text("reason").trim();val purchaseId=raw.text("purchaseId")
        checkApi(action in listOf("link","clear") && reason.length in 3..500,"Explain the reconciliation correction.")
        val purchases=portfolio.bonds(owner)+portfolio.shares(owner)
        if(action=="link")checkApi(purchases.any { it.text("id")==purchaseId && StatementMatching.canConfirm(item,it) },"Resolve inconsistent figures before linking this purchase.",409)
        val proof=(raw.text("proofDocumentId").ifBlank { purchases.firstOrNull { it.text("id")==purchaseId }?.text(if(item.text("kind")=="equity")"contractDocumentId" else "confirmationDocumentId").orEmpty() }).takeIf(String::isNotBlank)
        if(proof!=null)checkApi(ownedDocument(owner,proof).text("category")==if(item.text("kind")=="equity")"Share contract" else "Bond confirmation","Choose the original purchase confirmation.")
        val decision=mapOf("action" to action,"purchaseId" to if(action=="link")purchaseId else "","reason" to reason,"actor" to owner,"at" to java.time.Instant.now().toString(),"proofDocumentId" to proof,"purchaseFingerprint" to purchases.firstOrNull { it.text("id")==purchaseId }?.let(StatementMatching::fingerprint))
        val prior=db.query("SELECT decision FROM bond_statement_decisions WHERE owner_id=? AND item_id=?::uuid ORDER BY sequence DESC LIMIT 1",{r,_->json.read(r.getString(1))},owner,id).firstOrNull()
        // A retry returns the same correction; a stale different decision cannot replace newer intent.
        val retry=prior!=null && listOf("action","purchaseId","reason","proofDocumentId","purchaseFingerprint").all { prior[it]==decision[it] }
        if(!retry) {
            if(raw.containsKey("expectedDecisionAt"))checkApi(raw.text("expectedDecisionAt")==prior?.text("at").orEmpty(),"This reconciliation changed. Refresh before correcting it.",409)
            db.update("INSERT INTO bond_statement_decisions(owner_id,item_id,decision) VALUES (?,?::uuid,?::jsonb)",owner,id,json.write(decision))
        }
        val saved=item+mapOf("id" to id)
        return listOf(decorate(owner,saved,purchases,evidence(owner,listOf(saved))))
    }
}
