package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Base64

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class BondPurchaseLinksIntegrationTests {
    @Autowired lateinit var db: JdbcTemplate
    @Autowired lateinit var portfolio: PortfolioService
    @Autowired lateinit var links: BondPurchaseLinks
    @Autowired lateinit var documents: DocumentService
    @Autowired lateinit var json: JsonCodec
    @Autowired lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    companion object {
        @JvmStatic @DynamicPropertySource fun properties(p: DynamicPropertyRegistry) {
            p.add("gabo.admin-email") { "owner@example.test" }
            p.add("gabo.admin-password-hash") { Passwords.hash("synthetic-bond-link-password") }
            for(key in listOf("gabo.friend-email","gabo.friend-password-hash","gabo.push.firebase-project","gabo.push.apple-team","gabo.push.apple-key-id","gabo.push.apple-key","gabo.import-web-directory")) p.add(key) { "" }
        }
    }
    private fun bond(status: String, amount: Int): Record=mapOf("instrumentType" to "treasury","issuer" to "Government of Rwanda","currency" to "RWF","market" to "primary",
        "purchaseDate" to "2026-10-01","settlementDate" to "2026-10-23","bondName" to "SYNTHETIC/2026/5YR","isin" to "RWTEST123456","tenorYears" to 5,
        "faceValue" to amount,"pricePercent" to if(status=="submitted") 100 else 99,"accruedInterestPaid" to 0,"feesPaid" to if(status=="submitted") 11000 else 23000,
        "couponRate" to if(status=="submitted") 0 else .12,"withholdingTaxRate" to .05,"maturityDate" to "2031-10-23","firstCouponDate" to if(status=="submitted") "" else "2027-04-23",
        "couponDates" to if(status=="submitted") emptyList<String>() else listOf("2027-04-23","2031-10-23"),"couponFrequency" to 2,"scheduleConfidence" to "estimated","status" to status)
    private inner class Fixture(target: Record = bond("active",1000000), proof: Boolean = true) : AutoCloseable {
        val owner="bond-link-"+newId()
        val pending=portfolio.saveBond(owner,bond("submitted",2000000))
        val document=if(proof)documents.upload(owner,mapOf("uploadId" to newId().replace("-",""),"index" to 0,"count" to 1,
            "content" to Base64.getEncoder().encodeToString("%PDF-1.7\nsynthetic bond link\n%%EOF".toByteArray()),"metadata" to mapOf("category" to "Bond confirmation","documentDate" to "2026-10-23","label" to "Synthetic confirmation","instrumentName" to "Synthetic bond","issuer" to "BNR"))).text("documentId") else ""
        val purchase=portfolio.saveBond(owner,target+if(proof)mapOf("confirmationDocumentId" to document) else emptyMap())
        fun request(): Record=mapOf("confirmed" to true,"purchaseId" to purchase["id"],"pending" to pending,"purchase" to purchase)
        override fun close() {
            db.update("DELETE FROM investment_cash_ledger WHERE owner_id=?",owner)
            db.update("DELETE FROM bond_purchases WHERE owner_id=?",owner)
            db.update("DELETE FROM bond_uploaded_documents WHERE owner_id=?",owner)
        }
    }
    @Test fun linkKeepsActualPurchaseReceiptsAndPdfArchivesPendingAndIsIdempotent() {
        Fixture().use { f ->
            db.update("INSERT INTO coupon_receipts(owner_id,purchase_id,due_date,amount,received,method) VALUES (?,?::uuid,'2027-04-23',12000,true,'manual')",f.owner,f.purchase["id"])
            val before=portfolio.bond(f.owner,f.purchase.text("id"))!!
            assertEquals(before,links.link(f.owner,f.pending.text("id"),f.request()))
            assertEquals(before,links.link(f.owner,f.pending.text("id"),f.request()))
            assertEquals(listOf(f.purchase["id"]),portfolio.bonds(f.owner).map { it["id"] })
            assertEquals(before,portfolio.bondOrLinked(f.owner,f.pending.text("id")))
            assertNull(portfolio.bond(f.owner,f.pending.text("id")))
            val archived=db.queryForMap("SELECT face_value,fees_paid,linked_purchase_id::text FROM bond_purchases WHERE id=?::uuid",f.pending["id"])
            assertEquals(f.purchase["id"],archived["linked_purchase_id"])
            assertEquals("2000000.00",archived["face_value"].toString()); assertEquals("11000.00",archived["fees_paid"].toString())
            assertEquals(1L,db.queryForObject("SELECT count(*) FROM coupon_receipts WHERE owner_id=?",Long::class.java,f.owner))
            assertEquals(1L,db.queryForObject("SELECT count(*) FROM bond_uploaded_documents WHERE owner_id=?",Long::class.java,f.owner))
            assertThrows(ApiError::class.java) { portfolio.saveBond(f.owner,f.pending,f.pending.text("id")) }
        }
    }
    @Test fun rejectsMissingProofWrongAccountsSecuritiesDatesAndOverAllocation() {
        Fixture(proof=false).use { f -> assertThrows(ApiError::class.java) { links.link(f.owner,f.pending.text("id"),f.request()) }; assertEquals(2,portfolio.bonds(f.owner).size) }
        for(change in listOf(mapOf("isin" to "RWOTHER12345"),mapOf("settlementDate" to "2026-10-24"),mapOf("maturityDate" to "2032-10-23"),mapOf("faceValue" to 3000000))) {
            Fixture(bond("active",1000000)+change).use { f ->
                assertThrows(ApiError::class.java) { links.link(f.owner,f.pending.text("id"),f.request()) }
                assertEquals(2,portfolio.bonds(f.owner).size)
                assertThrows(ApiError::class.java) { links.link("other-"+newId(),f.pending.text("id"),f.request()) }
            }
        }
    }
    @Test fun rejectsStaleFinancialSnapshotsAndPendingActivityWithoutChangingEitherRecord() {
        Fixture().use { f ->
            val before=portfolio.bonds(f.owner)
            assertThrows(ApiError::class.java) { links.link(f.owner,f.pending.text("id"),f.request()+mapOf("purchase" to f.purchase+mapOf("feesPaid" to 1))) }
            assertEquals(before,portfolio.bonds(f.owner))
            db.update("INSERT INTO coupon_receipts(owner_id,purchase_id,due_date,amount,received,method) VALUES (?,?::uuid,'2027-04-23',0,false,'manual')",f.owner,f.pending["id"])
            assertThrows(ApiError::class.java) { links.link(f.owner,f.pending.text("id"),f.request()) }
            assertEquals(before,portfolio.bonds(f.owner))
        }
    }
    @Test fun migrationPreservesExistingPurchaseRowsAndStartsWithNoLinks() {
        Fixture().use { f ->
            org.springframework.transaction.support.TransactionTemplate(transactionManager).execute { tx ->
                val schema="bond_link_upgrade_"+newId().replace("-","")
                db.execute("CREATE SCHEMA $schema")
                db.execute("SET LOCAL search_path TO $schema,public")
                db.execute("CREATE TABLE $schema.bond_purchases (LIKE public.bond_purchases INCLUDING ALL)")
                db.execute("ALTER TABLE $schema.bond_purchases DROP COLUMN linked_purchase_id CASCADE")
                db.execute("ALTER TABLE $schema.bond_purchases DROP COLUMN linked_at CASCADE")
                val columns=db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name='bond_purchases' ORDER BY ordinal_position",String::class.java,schema).joinToString(",")
                db.update("INSERT INTO $schema.bond_purchases ($columns) SELECT $columns FROM public.bond_purchases WHERE owner_id=?",f.owner)
                val before=db.queryForList("SELECT to_jsonb(p)::text FROM $schema.bond_purchases p ORDER BY id",String::class.java)
                val migration=javaClass.getResource("/db/migration/V20__link_imported_bond_purchases.sql")!!.readText()
                db.execute(migration)
                val after=db.queryForList("SELECT (to_jsonb(p)-'linked_purchase_id'-'linked_at')::text FROM $schema.bond_purchases p ORDER BY id",String::class.java)
                assertEquals(before,after)
                assertEquals(2L,db.queryForObject("SELECT count(*) FROM $schema.bond_purchases WHERE linked_purchase_id IS NULL AND linked_at IS NULL",Long::class.java))
                tx.setRollbackOnly()
            }
        }
    }

    @Test fun databaseFailureRollsBackLinkAndLeavesBothPurchasesAvailable() {
        Fixture().use { f ->
            val function="fail_bond_link_test"
            db.execute("CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN IF NEW.owner_id='${f.owner}' AND NEW.linked_purchase_id IS NOT NULL THEN RAISE EXCEPTION 'Synthetic link failure'; END IF; RETURN NEW; END; \$\$")
            db.execute("CREATE TRIGGER fail_bond_link_test AFTER UPDATE ON bond_purchases FOR EACH ROW EXECUTE FUNCTION $function()")
            try {
                assertThrows(Exception::class.java) { links.link(f.owner,f.pending.text("id"),f.request()) }
                assertEquals(2,portfolio.bonds(f.owner).size)
                assertNull(db.queryForMap("SELECT linked_purchase_id FROM bond_purchases WHERE id=?::uuid",f.pending["id"])["linked_purchase_id"])
            } finally { db.execute("DROP TRIGGER fail_bond_link_test ON bond_purchases");db.execute("DROP FUNCTION $function()") }
        }
    }
}
