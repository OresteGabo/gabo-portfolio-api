package dev.orestegabo.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

/** Link an estimate to existing personal confirmation evidence without rewriting either purchase's financial terms. */
@Service
class BondPurchaseLinks(private val db: JdbcTemplate, private val portfolio: PortfolioService,
    private val validation: PurchaseValidation, private val documents: DocumentService) {
    @Transactional fun link(owner: String, id: String, raw: Record): Record {
        val targetId=raw.text("purchaseId")
        checkApi(uuid(id) && uuid(targetId) && id!=targetId && raw["confirmed"]==true,"Choose an imported bond confirmation and confirm the link.")
        lockAccount(db,owner)
        val row=db.queryForList("SELECT linked_purchase_id::text FROM bond_purchases WHERE owner_id=? AND id=?::uuid FOR UPDATE",owner,id).singleOrNull()
            ?: throw ApiError(404,"Pending bond purchase not found.")
        row["linked_purchase_id"]?.toString()?.let { linked ->
            checkApi(linked==targetId,"This pending purchase is already linked to another purchase.",409)
            return portfolio.bond(owner,linked) ?: throw ApiError(409,"The linked purchase is no longer available.")
        }
        val pending=portfolio.bond(owner,id) ?: throw ApiError(404,"Pending bond purchase not found.")
        val purchase=portfolio.bond(owner,targetId) ?: throw ApiError(404,"Imported bond purchase not found.")
        checkApi(purchase.text("id")==targetId && matches(pending,purchase),"This confirmation does not match the pending bond or applied amount.",409)
        checkApi(sameFinancialRecord(validation.bond(pending),validation.bond(raw["pending"].record())) &&
            sameFinancialRecord(validation.bond(purchase),validation.bond(raw["purchase"].record())),"These purchases changed. Refresh before linking.",409)
        val proof=documents.ownedEvidenceId(owner,purchase.text("confirmationDocumentId"),"Bond confirmation")
        checkApi(proof!=null && proof==documents.ownedEvidenceId(owner,raw["purchase"].record().text("confirmationDocumentId"),"Bond confirmation"),
            "The imported purchase needs its personal confirmation PDF.",409)
        val receipts=db.queryForObject("SELECT count(*) FROM coupon_receipts WHERE owner_id=? AND purchase_id=?::uuid",Long::class.java,owner,id) ?: 0
        val ledger=db.queryForObject("SELECT count(*) FROM investment_cash_ledger WHERE owner_id=? AND payload->>'holdingId'=?",Long::class.java,owner,id) ?: 0
        checkApi(receipts==0L && ledger==0L,"This pending purchase has recorded activity. Review it before linking.",409)
        db.update("UPDATE bond_purchases SET linked_purchase_id=?::uuid,linked_at=now(),updated_at=now() WHERE owner_id=? AND id=?::uuid",targetId,owner,id)
        return portfolio.bond(owner,targetId)!!
    }
    private fun matches(pending: Record, purchase: Record): Boolean {
        fun security(p: Record)=p.text("isin").trim().uppercase().removeSuffix("/MN")
        return pending.text("status")=="submitted" && purchase.text("status") in listOf("active","matured","sold") &&
            listOf("issuer","currency","market").all { pending.text(it)==purchase.text(it) } &&
            pending.text("issuer")=="Government of Rwanda" && pending.text("currency")=="RWF" && pending.text("market")=="primary" &&
            pending.text("settlementDate")==purchase.text("settlementDate") && pending.text("maturityDate")==purchase.text("maturityDate") &&
            (security(pending).isBlank() || security(pending)==security(purchase)) &&
            purchase.decimal("faceValue").signum()>0 && purchase.decimal("faceValue")<=pending.decimal("faceValue") &&
            purchase.text("confirmationDocumentId").isNotBlank()
    }
}

@RestController
@RequestMapping("/api/bonds/purchases")
class BondPurchaseLinksController(private val links: BondPurchaseLinks) {
    @PostMapping("/{id}/link-imported") fun link(@PathVariable id: String,@RequestBody body: Record)=
        mapOf("purchase" to links.link(currentAccount().id,id,body))
}
