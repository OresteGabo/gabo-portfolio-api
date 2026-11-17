package dev.orestegabo.portfolio

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.jdbc.core.JdbcTemplate

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ShareOrderIntegrationTests {
    @Autowired lateinit var db: JdbcTemplate
    @Autowired lateinit var orders: ShareOrders
    @Autowired lateinit var portfolio: PortfolioService
    companion object {
        @JvmStatic @DynamicPropertySource fun properties(p: DynamicPropertyRegistry) {
            p.add("gabo.admin-email") { "owner@example.test" }
            p.add("gabo.admin-password-hash") { Passwords.hash("synthetic-share-order-password") }
            for(key in listOf("gabo.friend-email","gabo.friend-password-hash","gabo.push.firebase-project","gabo.push.apple-team","gabo.push.apple-key-id","gabo.push.apple-key","gabo.import-web-directory")) p.add(key) { "" }
        }
    }
    private fun request()=mapOf<String,Any?>("ticker" to "BOK","securityName" to "SYNTHETIC BK Group","shares" to 1000,"pricePerShare" to 720,"requestDate" to "2026-11-13","expectedExecutionDate" to "2026-11-16","version" to 0)
    private fun executed()=mapOf<String,Any?>("instrumentType" to "equity","exchange" to "RSE","currency" to "RWF","status" to "active","ticker" to "BOK","securityName" to "SYNTHETIC BK Group","isin" to "RWTESTBOK001","shares" to 1000,"pricePerShare" to 720,"tradeDate" to "2026-11-16","settlementDate" to "2026-11-18","grossConsideration" to 720000,"brokerageCommission" to 12312,"rseTransactionFee" to 0,"csdTransactionLevy" to 0,"cmaTransactionFee" to 0,"totalCharges" to 12312,"netAmountPayable" to 732312)
    private fun clean(owner: String) { db.update("DELETE FROM share_orders WHERE owner_id=?",owner);db.update("DELETE FROM equity_purchases WHERE owner_id=?",owner) }
    @Test fun pendingOrdersAreIsolatedAndCannotAffectOwnedShares() {
        val owner="share-order-"+newId();val other="share-order-other-"+newId();val id=newId()
        try {
            val saved=orders.save(owner,id,request())
            assertEquals(1,orders.list(owner).size)
            assertTrue(orders.list(other).isEmpty())
            assertTrue(portfolio.shares(owner).isEmpty())
            assertThrows(ApiError::class.java) { orders.cancel(other,id,mapOf("version" to 1)) }
            assertThrows(ApiError::class.java) { orders.save(owner,id,request()) }
            assertEquals(1,orders.list(owner).size)
            orders.cancel(owner,id,mapOf("version" to saved["version"]))
            assertTrue(orders.list(owner).isEmpty())
            assertTrue(portfolio.shares(owner).isEmpty())
        } finally { clean(owner);clean(other) }
    }
    @Test fun executionIsAtomicIdempotentAndCannotSilentlyCloseAPartialFill() {
        val owner="share-order-execution-"+newId();val id=newId()
        try {
            orders.save(owner,id,request())
            val envelope=mapOf("confirmed" to true,"version" to 1,"purchase" to executed())
            assertThrows(ApiError::class.java) { orders.confirm(owner,id,envelope+mapOf("purchase" to executed()+mapOf("shares" to 500))) }
            assertTrue(portfolio.shares(owner).isEmpty())
            assertEquals(1,orders.list(owner).size)
            val first=orders.confirm(owner,id,envelope)
            val retry=orders.confirm(owner,id,envelope)
            assertEquals(first.text("id"),retry.text("id"))
            assertEquals(1,portfolio.shares(owner).size)
            assertTrue(orders.list(owner).isEmpty())
            assertThrows(ApiError::class.java) { orders.save(owner,id,request()+mapOf("version" to 2)) }
        } finally { clean(owner) }
    }
}
