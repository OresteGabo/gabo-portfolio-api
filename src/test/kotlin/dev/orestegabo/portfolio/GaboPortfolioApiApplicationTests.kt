package dev.orestegabo.portfolio

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.time.Instant

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GaboPortfolioApiApplicationTests {
    @LocalServerPort var port: Int=0
    @Autowired lateinit var db: JdbcTemplate
    @Autowired lateinit var json: JsonCodec
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var lifecycle: LifecycleNotifications
    @Autowired lateinit var notifications: PriceNotifications
    @Autowired lateinit var history: MarketHistory
    @Autowired lateinit var importer: WebSeedImporter
    private val http=HttpClient.newHttpClient()
    companion object {
        private const val password="synthetic-test-password-123"
        @JvmStatic @DynamicPropertySource fun properties(p: DynamicPropertyRegistry) {
            p.add("gabo.admin-email") { "owner@example.test" }
            p.add("gabo.admin-password-hash") { Passwords.hash(password) }
            p.add("gabo.allowed-origins") { "https://web.example.test" }
            p.add("gabo.cookie-secure") { false }
            p.add("gabo.cron-secret") { "synthetic-cron-secret" }
            p.add("gabo.snapshot-secret") { "synthetic-snapshot-secret" }
        }
    }
    @BeforeEach fun clean() {
        // This datasource is supplied exclusively by the isolated Testcontainers PostgreSQL bean.
        db.execute("TRUNCATE account_devices,coupon_receipts,notification_preferences,price_push_deliveries,push_devices,notifications,bond_issuance_documents,bond_purchases,equity_purchases,bond_users,bond_sessions,bond_registration,bond_auth_attempts,bond_uploaded_documents,bond_document_uploads,bond_statement_items,equity_market_observations,equity_market_collection,bond_market_observations,bond_data_migrations")
    }
    private fun call(path: String,method: String="GET",body: Any?=null,cookie: String?=null,origin: String?="https://web.example.test",type: String="application/json"): HttpResponse<String> {
        val builder=HttpRequest.newBuilder(URI("http://localhost:$port/api/bonds$path"))
        if(origin!=null)builder.header("Origin",origin)
        if(cookie!=null)builder.header("Cookie",cookie)
        if(body!=null)builder.header("Content-Type",type)
        return http.send(builder.method(method,if(body==null)HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(if(body is String)body else json.write(body))).build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun data(response: HttpResponse<String>)=json.read(response.body())
    private fun login(email: String="owner@example.test"): String {
        val response=call("/auth/login","POST",mapOf("email" to email,"password" to password))
        assertEquals(200,response.statusCode(),response.body())
        val cookie=response.headers().firstValue("set-cookie").orElseThrow()
        assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"))
        return cookie.substringBefore(';')
    }
    private fun user(): Pair<String,String> {
        val id=newId();val email="$id@example.test"
        db.update("INSERT INTO bond_users(id,email,password_hash) VALUES (?,?,?)",id,email,Passwords.hash(password))
        return id to login(email)
    }
    private fun bond(status: String="submitted"): Record=mapOf("instrumentType" to "treasury","issuer" to "Government of Rwanda","currency" to "RWF","market" to "primary","purchaseDate" to "2026-10-01","settlementDate" to "2026-10-23","bondName" to "SYNTHETIC/2026/5YR","isin" to "RWTEST123456","tenorYears" to 5,"faceValue" to 1200000,"pricePercent" to 100,"accruedInterestPaid" to 0,"feesPaid" to 11000,"couponRate" to .12,"maturityDate" to "2031-10-23","couponFrequency" to 2,"scheduleConfidence" to "estimated","status" to status)
    private fun equity(): Record=mapOf("instrumentType" to "equity","exchange" to "RSE","currency" to "RWF","securityName" to "Synthetic I&M","ticker" to "IMR","isin" to "RWTEST123456","tradeDate" to "2026-09-24","settlementDate" to "2026-09-28","shares" to 1000,"pricePerShare" to 90,"grossConsideration" to 90000,"brokerageCommission" to 1000,"rseTransactionFee" to 50,"csdTransactionLevy" to 25,"cmaTransactionFee" to 25,"totalCharges" to 1100,"netAmountPayable" to 91100,"broker" to "BK Capital","contractNote" to "20260004454#01")
    private fun upload(cookie: String,bytes: ByteArray="%PDF-1.7\nsynthetic fixture".toByteArray(),uploadId: String="a".repeat(32),index: Int=0,count: Int=1): HttpResponse<String> = call("/documents","POST",mapOf("uploadId" to uploadId,"index" to index,"count" to count,"content" to Base64.getEncoder().encodeToString(bytes),"metadata" to mapOf("category" to "Bond confirmation","documentDate" to "2026-10-23","label" to "Synthetic result","instrumentName" to "SYNTHETIC/2026/5YR","issuer" to "BNR")),cookie)

    @Test fun `sessions are opaque revocable and invalidated by credential changes`() {
        assertEquals(false,data(call("/auth/session"))["authenticated"])
        val cookie=login()
        assertEquals(true,data(call("/auth/session",cookie=cookie))["authenticated"])
        val row=db.queryForList("SELECT token_hash FROM bond_sessions").single()
        assertNotEquals(cookie.substringAfter('='),row["token_hash"])
        assertEquals(200,call("/auth/logout","POST",cookie=cookie).statusCode())
        assertEquals(401,call("/purchases",cookie=cookie).statusCode())
        val (id,other)=user()
        db.update("UPDATE bond_users SET password_hash=? WHERE id=?",Passwords.hash("a-different-long-password"),id)
        assertEquals(false,data(call("/auth/session",cookie=other))["authenticated"])
    }
    @Test fun `mutation origin content type and bounded bodies are enforced`() {
        assertEquals(403,call("/auth/login","POST",emptyMap<String,String>(),origin=null).statusCode())
        assertEquals(403,call("/auth/login","POST",emptyMap<String,String>(),origin="https://evil.example").statusCode())
        assertEquals(415,call("/auth/login","POST","{}",type="text/plain").statusCode())
        assertEquals(413,call("/auth/login","POST"," ".repeat(4100)).statusCode())
        assertEquals(400,call("/auth/login","POST","{bad json").statusCode())
        assertEquals(400,call("/auth/login","POST","{} {}").statusCode())
        val preflight=HttpRequest.newBuilder(URI("http://localhost:$port/api/bonds/purchases")).header("Origin","https://web.example.test").header("Access-Control-Request-Method","POST").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build()
        val response=http.send(preflight,HttpResponse.BodyHandlers.ofString())
        assertEquals(200,response.statusCode());assertEquals("https://web.example.test",response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow())
    }
    @Test fun `login attempts are limited without exposing account existence`() {
        repeat(5) { assertEquals(401,call("/auth/login","POST",mapOf("email" to "unknown@example.test","password" to "wrong")).statusCode()) }
        assertEquals(429,call("/auth/login","POST",mapOf("email" to "unknown@example.test","password" to "wrong")).statusCode())
    }
    @Test fun `pending confirmation updates the same purchase and rejects duplicates`() {
        val cookie=login()
        val created=call("/purchases","POST",bond(),cookie)
        assertEquals(201,created.statusCode(),created.body())
        val purchase=data(created)["purchase"].record();val id=purchase.text("id")
        assertEquals(1211000.0,purchase.number("amountInvested"))
        assertEquals(409,call("/purchases","POST",bond(),cookie).statusCode())
        val confirmed=call("/purchases/$id","PUT",bond("active")+mapOf("couponDates" to listOf("2027-04-23"),"firstCouponDate" to "2027-04-23"),cookie)
        assertEquals(200,confirmed.statusCode(),confirmed.body())
        assertEquals(id,data(confirmed)["purchase"].record()["id"])
        assertEquals(1,data(call("/purchases",cookie=cookie)).records("purchases").size)
        assertEquals(200,upload(cookie).statusCode())
        assertEquals(1,data(call("/documents",cookie=cookie)).records("documents").size)
    }
    @Test fun `accounts cannot access or mutate each others purchases and PDFs`() {
        val (_,a)=user();val (_,b)=user()
        val id=data(call("/purchases","POST",bond(),a))["purchase"].record().text("id")
        assertEquals(404,call("/purchases/$id",cookie=b).statusCode())
        assertEquals(404,call("/purchases/$id","PUT",bond(),b).statusCode())
        assertEquals(404,call("/purchases/$id","DELETE",cookie=b).statusCode())
        val pdf=data(upload(a)).text("documentId")
        assertEquals(404,call("/documents/$pdf",cookie=b).statusCode())
        assertTrue(data(call("/documents",cookie=b)).records("documents").isEmpty())
        assertEquals(403,call("/auth/accounts",cookie=b).statusCode())
        assertEquals(403,call("/auth/registration","POST",mapOf("email" to "x@example.test"),b).statusCode())
    }
    @Test fun `PDF chunks are private ordered idempotent bounded and retryable`() {
        val cookie=login()
        val first=ByteArray(512*1024) { 32 }.also { "%PDF-1.7".toByteArray().copyInto(it) }
        val tail="last bytes".toByteArray();val id="b".repeat(32)
        assertEquals(emptyMap<String,Any>(),data(upload(cookie,tail,id,1,2)))
        assertTrue(data(call("/documents",cookie=cookie)).records("documents").isEmpty())
        val result=upload(cookie,first,id,0,2);assertEquals(200,result.statusCode(),result.body())
        val documentId=data(result).text("documentId")
        val downloaded=http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/bonds/documents/$documentId")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofByteArray())
        assertArrayEquals(first+tail,downloaded.body())
        val again=upload(cookie,first+tail,"c".repeat(32))
        assertEquals(400,again.statusCode()) // Single chunks are limited to 512 KiB.
        assertEquals(400,upload(cookie,"not a PDF".toByteArray()).statusCode())
        val small=upload(cookie);val duplicate=upload(cookie,uploadId="d".repeat(32))
        assertEquals(data(small)["documentId"],data(duplicate)["documentId"])
        assertFalse(call("/documents",cookie=cookie).body().contains("content\""))
    }
    @Test fun `share fees and duplicates are validated while public history exposes only prices`() {
        val (_,cookie)=user()
        assertEquals(400,call("/equities","POST",equity()+mapOf("totalCharges" to 10),cookie).statusCode())
        val result=call("/equities","POST",equity(),cookie);assertEquals(201,result.statusCode(),result.body())
        assertEquals(409,call("/equities","POST",equity(),cookie).statusCode())
        val body=call("/rse/equity-history?tickers=IMR&period=all")
        assertEquals(200,body.statusCode(),body.body())
        val row=data(body).records("tradeObservations").single()
        assertEquals(90.0,row.number("price"));assertEquals("contract",row["source"])
        assertEquals(setOf("ticker","date","price","low","high","source"),row.keys)
        assertFalse(body.body().contains("BK Capital"));assertFalse(body.body().contains("20260004454"))
        assertEquals(400,call("/rse/equity-history?tickers=IMR&period=invalid").statusCode())
        assertEquals(400,call("/rse/equity-history?tickers=bad").statusCode())
    }
    @Test fun `statement debits create reminders without inventing investment principal`() {
        val cookie=login()
        val item=mapOf("kind" to "bond","date" to "2026-10-24","amount" to 1200000,"title" to "Synthetic bond debit","currency" to "RWF","broker" to "BK Capital")
        val result=call("/statement-items","POST",mapOf("items" to listOf(item)),cookie)
        assertEquals(200,result.statusCode(),result.body());assertEquals("missing",data(result).records("rows").single()["state"])
        assertTrue(data(call("/purchases",cookie=cookie)).records("purchases").isEmpty())
        assertEquals(1,data(call("/statement-items",cookie=cookie)).records("items").size)
        lifecycle.sync("owner",notificationToday())
        assertEquals(1,notifications.list("owner",null).records("notifications").count { it["kind"]=="documents" })
        val saved=data(call("/purchases","POST",bond("active"),cookie))["purchase"].record()
        assertTrue(data(call("/statement-items",cookie=cookie)).records("items").isEmpty())
        lifecycle.sync("owner",notificationToday())
        assertEquals(true,notifications.list("owner",null).records("notifications").first { it["kind"]=="documents" }["resolved"])
        call("/purchases/${saved.text("id")}","DELETE",cookie=cookie)
        lifecycle.sync("owner",notificationToday())
        assertFalse(notifications.list("owner",null).records("notifications").first { it["kind"]=="documents" }["resolved"]==true)
    }
    @Test fun `invitations are single use and self deletion removes private data and revokes every session`() {
        val owner=login()
        val token=data(call("/auth/registration","POST",mapOf("email" to "invitee@example.test"),owner)).text("invite")
        val input=mapOf("email" to "invitee@example.test","password" to password,"invite" to token)
        val registered=call("/auth/register","POST",input);assertEquals(201,registered.statusCode(),registered.body())
        val id=data(registered)["user"].record().text("id");val cookie=registered.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
        assertEquals(403,call("/auth/register","POST",input).statusCode())
        call("/purchases","POST",bond(),cookie);call("/equities","POST",equity(),cookie);upload(cookie)
        val second=login("invitee@example.test")
        assertEquals(403,call("/auth/account","DELETE",mapOf("password" to "wrong"),cookie).statusCode())
        val deleted=call("/auth/account","DELETE",mapOf("password" to password),cookie);assertEquals(200,deleted.statusCode(),deleted.body())
        for(table in listOf("bond_purchases","equity_purchases","bond_uploaded_documents","bond_document_uploads","bond_statement_items")) assertEquals(0,db.queryForObject("SELECT count(*) FROM $table WHERE owner_id=?",Int::class.java,id))
        assertEquals(401,call("/purchases",cookie=second).statusCode())
        assertTrue(data(call("/rse/equity-history?tickers=IMR")).records("tradeObservations").isEmpty())
        assertEquals(403,call("/auth/account","DELETE",mapOf("password" to password),owner).statusCode())
    }
    @Test fun `market observations never create fake days or accept older overwrites`() {
        val source=mapOf("status" to "available","stale" to false,"publishedAt" to "2026-10-21T12:27:00Z","checkedAt" to "2026-10-21T14:00:00Z")
        val input=mapOf("source" to source,"quotes" to listOf(mapOf("ticker" to "IMR","closing" to 115)))
        assertEquals(1,history.record(input)["written"]);assertEquals(0,history.record(input)["written"])
        assertEquals(0,history.record(input+mapOf("source" to source+mapOf("checkedAt" to "2026-10-21T13:00:00Z")))["written"])
        assertEquals("missing-market-date",history.record(input+mapOf("source" to source+mapOf("publishedAt" to null)))["status"])
        assertEquals("unusable-source",history.record(input+mapOf("source" to source+mapOf("stale" to true)))["status"])
        assertEquals(1,history.history(listOf("IMR"),"all").records("observations").size)
        assertEquals(401,call("/rse/daily").statusCode());assertEquals(401,call("/rse/snapshot").statusCode())
    }
    @Test fun `price changes alert only eligible holders once and unread data is isolated`() {
        val owner=login();val (uid,other)=user()
        assertEquals(201,call("/equities","POST",equity(),owner).statusCode())
        assertEquals(201,call("/equities","POST",equity(),other).statusCode())
        fun close(day: String,price: Int)=history.record(mapOf("source" to mapOf("status" to "available","stale" to false,
            "publishedAt" to "${day}T12:00:00Z","checkedAt" to "${day}T13:00:00Z"),"quotes" to listOf(mapOf("ticker" to "IMR","closing" to price))))
        close("2026-10-21",110);assertEquals(0,notifications.collect("2026-10-21"))
        close("2026-10-22",110);assertEquals(0,notifications.collect("2026-10-22"))
        close("2026-10-23",115);assertEquals(2,notifications.collect("2026-10-23"));assertEquals(0,notifications.collect("2026-10-23"))
        val data=data(call("/notifications",cookie=owner));assertEquals(1,data["unreadCount"])
        val alert=data.records("notifications").single();assertEquals("IMR",alert["ticker"])
        assertEquals(5000.0,alert.number("impact"));assertEquals(23900.0,alert.number("totalGain"),.01)
        assertEquals(200,call("/notifications/read","POST",mapOf("ids" to listOf(alert["id"])),other).statusCode())
        assertEquals(1,data(call("/notifications",cookie=owner))["unreadCount"])
        assertEquals(200,call("/notifications/read","POST",mapOf("ids" to listOf(alert["id"])),owner).statusCode())
        assertEquals(0,data(call("/notifications",cookie=owner))["unreadCount"])
        val device=newId();val token="synthetic-push-token-for-test"
        val payload=mapOf("deviceId" to device,"platform" to "android","token" to token,"language" to "en","environment" to "production")
        assertEquals(200,call("/notifications/devices","POST",payload,owner).statusCode())
        notifications.collect("2026-10-23")
        assertEquals(0,db.queryForObject("SELECT count(*) FROM price_push_deliveries",Int::class.java)) // Already-read alerts are not pushed.
        assertEquals(200,call("/notifications/devices","POST",payload,other).statusCode())
        assertEquals(uid,db.queryForObject("SELECT owner_id FROM push_devices WHERE id=?::uuid",String::class.java,device))
        assertEquals(200,call("/auth/logout","POST",mapOf("deviceId" to device),other).statusCode())
        assertEquals(0,db.queryForObject("SELECT count(*) FROM push_devices",Int::class.java))
        close("2026-10-26",90);assertEquals(2,notifications.collect("2026-10-26"))
        assertTrue(data(call("/notifications",cookie=owner)).records("notifications").first().number("impact")<0)
        assertEquals(401,call("/notifications").statusCode())
    }

    @Test fun `coupon confirmation is private idempotent reversible and automatic mode starts today`() {
        val cookie=login();val (_,other)=user();val today=notificationToday()
        val dates=listOf(today.minusDays(1),today,today.plusDays(1)).map { it.toString() }
        val saved=data(call("/purchases","POST",bond("active")+mapOf("couponDates" to dates,"firstCouponDate" to dates.first(),"scheduleConfidence" to "confirmed"),cookie))["purchase"].record()
        val id=saved.text("id");assertTrue(uuid(id))
        assertEquals(false,lifecycle.preferences("owner")["autoCoupon"])
        lifecycle.sync("owner",today)
        assertTrue(lifecycle.receipts("owner").isEmpty())
        lifecycle.receive("owner",id,today.toString(),true,today);lifecycle.receive("owner",id,today.toString(),true,today)
        assertEquals(1,lifecycle.receipts("owner").size)
        val bad=call("/notifications/coupons","POST",mapOf("purchaseId" to id,"dueDate" to today.toString(),"received" to true),other)
        assertEquals(404,bad.statusCode())
        assertThrows(ApiError::class.java) { lifecycle.receive("owner",id,today.plusDays(1).toString(),true,today) }
        lifecycle.receive("owner",id,today.toString(),false,today)
        lifecycle.update("owner",mapOf("autoCoupon" to true));lifecycle.sync("owner",today)
        assertFalse(lifecycle.receipts("owner").single()["received"] as Boolean) // Manual undo wins over automatic marking.
        lifecycle.sync("owner",today.plusDays(1));lifecycle.sync("owner",today.plusDays(1))
        val receipts=lifecycle.receipts("owner");assertEquals(2,receipts.size)
        assertFalse(receipts.any { it["dueDate"]==today.minusDays(1).toString() })
        assertEquals("automatic",receipts.first { it["dueDate"]==today.plusDays(1).toString() }["method"])
        val categories=notificationCategories.associateWith { it!="coupons" }
        lifecycle.update("owner",mapOf("categories" to categories))
        assertTrue(notifications.list("owner",null).records("notifications").none { it["kind"]=="coupons" })
    }
    @Test fun `general reminders reports targets and new devices are deduplicated`() {
        val cookie=login();val today=notificationToday()
        assertEquals(201,call("/purchases","POST",bond(),cookie).statusCode())
        lifecycle.update("owner",mapOf("monthlyTarget" to 100))
        assertEquals(201,call("/equities","POST",equity()+mapOf("tradeDate" to today.toString(),"settlementDate" to today.toString()),cookie).statusCode())
        lifecycle.sync("owner",today);lifecycle.sync("owner",today)
        val items=notifications.list("owner",null).records("notifications")
        assertEquals(1,items.count { it["kind"]=="targets" })
        assertEquals(1,items.count { it["kind"]=="pending" })
        val first=newId();val second=newId()
        lifecycle.signIn("owner",first,"ios");lifecycle.signIn("owner",second,"android");lifecycle.signIn("owner",second,"android")
        val report=mapOf("exportId" to "a".repeat(32),"date" to today.toString())
        lifecycle.report("owner",report);lifecycle.report("owner",report)
        val all=notifications.list("owner",null).records("notifications")
        assertEquals(1,all.count { it["kind"]=="security" });assertEquals(1,all.count { it["kind"]=="reports" })
        assertTrue(notifications.list(user().first,null).records("notifications").none { it["kind"] in listOf("security","reports","targets") })
    }

    @Test fun `subscription closing and maturity reminders use real dates and do not repeat`() {
        val cookie=login()
        val saved=data(call("/purchases","POST",bond("active")+mapOf("maturityDate" to "2026-11-15"),cookie))
        assertTrue(uuid(saved["purchase"].record().text("id")))
        val day=java.time.LocalDate.parse("2026-10-20")
        lifecycle.sync("owner",day);lifecycle.sync("owner",day)
        val items=notifications.list("owner",null).records("notifications")
        assertEquals(1,items.count { it["kind"]=="subscriptions" && it["stage"]=="closing" })
        assertEquals(1,items.count { it["kind"]=="maturities" && it["dueDate"]=="2026-11-15" })
    }

    @Test fun `invalid dates and raw internal errors never leak into API responses`() {
        val cookie=login()
        val invalid=call("/purchases","POST",bond()+mapOf("purchaseDate" to "2026-02-30"),cookie)
        assertEquals(400,invalid.statusCode());assertFalse(invalid.body().contains("Exception"))
        val extra=call("/purchases","POST",bond()+mapOf("owner_id" to "victim"),cookie)
        assertEquals(201,extra.statusCode());assertEquals("owner",db.queryForObject("SELECT owner_id FROM bond_purchases",String::class.java))
    }
    @Test fun `importer preserves IDs dates PDF URLs and is one time without restoring deletions`(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
        val raw=bond("active")+mapOf("id" to newId(),"createdAt" to "2026-10-01T10:00:00Z")
        java.nio.file.Files.createDirectories(root.resolve(".data"));java.nio.file.Files.createDirectories(root.resolve("private/bonds/documents"))
        java.nio.file.Files.writeString(root.resolve(".data/bonds-portfolio.json"),json.write(mapOf("purchases" to listOf(raw))))
        java.nio.file.Files.writeString(root.resolve(".data/equity-portfolio.json"),json.write(mapOf("holdings" to listOf(equity()+mapOf("id" to "legacy-share-lot","createdAt" to "2026-09-24T10:00:00Z")))))
        java.nio.file.Files.writeString(root.resolve("private/bonds/documents/result.pdf"),"%PDF-1.7 synthetic")
        java.nio.file.Files.writeString(root.resolve("private/bonds/bond-documents.json"),json.write(listOf(mapOf("id" to "legacy-result","fileName" to "result.pdf","originalFileName" to "result.pdf","label" to "Synthetic","category" to "Bond confirmation","documentDate" to "2026-10-23","instrumentName" to "SYNTHETIC","issuer" to "BNR"))))
        importer.importDirectory(root);importer.importDirectory(root)
        assertEquals(1,db.queryForObject("SELECT count(*) FROM bond_purchases",Int::class.java))
        assertEquals(raw["id"],db.queryForObject("SELECT id::text FROM bond_purchases",String::class.java))
        val cookie=login();assertEquals(200,call("/documents/legacy-result",cookie=cookie).statusCode())
        assertEquals("legacy-share-lot",data(call("/equities",cookie=cookie)).records("holdings").single().text("id"))
        assertEquals(1,db.queryForObject("SELECT count(*) FROM equity_purchases",Int::class.java))
        call("/purchases/${raw["id"]}","DELETE",cookie=cookie);importer.importDirectory(root)
        assertEquals(0,db.queryForObject("SELECT count(*) FROM bond_purchases",Int::class.java))
    }
    @Test fun `issuance results enrich every matching pending application and manual confirmation creates no personal PDF`() {
        val owner=login();val (_,other)=user()
        val issue=json.read(javaClass.getResourceAsStream("/bnr-issuance.json")!!.bufferedReader().use { it.readText() })
        val pending=bond()+mapOf("notes" to "Pending application. Allocation, executed price and coupon are unconfirmed; stored price is provisional.","bondName" to "New 20-year bond","isin" to "","tenorYears" to 20,"maturityDate" to issue["maturityDate"],"couponRate" to 0,"couponDates" to emptyList<String>(),"firstCouponDate" to "","scheduleConfidence" to "estimated")
        val a=data(call("/purchases","POST",pending,owner))["purchase"].record()
        val b=data(call("/purchases","POST",pending+mapOf("faceValue" to 2000000),other))["purchase"].record()
        val wrong=data(call("/purchases","POST",pending+mapOf("isin" to "RW000WRONGCODE","faceValue" to 3000000),owner))["purchase"].record()
        val bytes=Base64.getEncoder().encodeToString("%PDF-1.7 synthetic public report".toByteArray())
        val uploaded=call("/documents","POST",mapOf("uploadId" to "e".repeat(32),"count" to 1,"index" to 0,"content" to bytes,"metadata" to mapOf("label" to "Bond issuance results","category" to "Bond issuance","documentDate" to issue["issuanceDate"],"instrumentName" to issue["bondName"],"issuer" to issue["issuer"],"issuance" to issue)),owner)
        assertEquals(200,uploaded.statusCode(),uploaded.body());assertEquals(2,data(uploaded)["updatedPendingPurchases"])
        fun purchase(id: String,cookie: String)=data(call("/purchases/$id",cookie=cookie))["purchase"].record()
        val enriched=purchase(a.text("id"),owner)
        assertEquals("submitted",enriched["status"]);assertEquals(1200000.0,enriched.number("faceValue"));assertEquals(a.number("amountInvested"),enriched.number("amountInvested"))
        assertEquals(issue.number("couponRate"),enriched.number("couponRate"));assertEquals(issue.strings("couponDates"),enriched.strings("couponDates"))
        assertEquals(2000000.0,purchase(b.text("id"),other).number("faceValue"));assertFalse(purchase(wrong.text("id"),owner)["publishedTermsAvailable"] as Boolean)
        val proof=mapOf("confirmed" to true,"issuanceDocumentId" to enriched["issuanceDocumentId"])
        assertEquals(404,call("/purchases/${a.text("id")}/confirm","POST",proof,other).statusCode())
        val confirmed=call("/purchases/${a.text("id")}/confirm","POST",proof,owner)
        assertEquals(200,confirmed.statusCode(),confirmed.body());assertEquals("manual",data(confirmed)["purchase"].record()["confirmationMethod"])
        assertEquals("active",purchase(a.text("id"),owner)["status"]);assertEquals("",purchase(a.text("id"),owner)["notes"]);assertEquals("submitted",purchase(b.text("id"),other)["status"])
        assertEquals(0,db.queryForObject("SELECT count(*) FROM bond_uploaded_documents",Int::class.java))
        assertEquals(200,call("/purchases/${a.text("id")}/confirm","POST",proof,owner).statusCode())
        val personal=data(call("/documents","POST",mapOf("uploadId" to "f".repeat(32),"count" to 1,"index" to 0,"content" to Base64.getEncoder().encodeToString("%PDF-1.7 synthetic personal confirmation".toByteArray()),"metadata" to mapOf("label" to "Bond confirmation","category" to "Bond confirmation","documentDate" to "2026-10-23","instrumentName" to issue["bondName"],"issuer" to issue["issuer"])),owner)).text("documentId")
        val attached=call("/purchases/${a.text("id")}","PUT",purchase(a.text("id"),owner)+mapOf("confirmationDocumentId" to personal),owner)
        assertEquals(200,attached.statusCode(),attached.body());assertEquals("document",data(attached)["purchase"].record()["confirmationMethod"])
        assertEquals(personal,purchase(a.text("id"),owner)["confirmationDocumentId"])
        assertEquals(3,db.queryForObject("SELECT count(*) FROM bond_purchases",Int::class.java))
        val later=data(call("/purchases","POST",pending+mapOf("faceValue" to 4000000),owner))["purchase"].record()
        assertTrue(later["publishedTermsAvailable"] as Boolean);assertEquals(issue.number("couponRate"),later.number("couponRate"))
        val conflict=data(call("/purchases","POST",pending+mapOf("faceValue" to 5000000,"pricePercent" to 90),owner))["purchase"].record()
        assertFalse(conflict["canConfirmWithoutPdf"] as Boolean)
        assertEquals(409,call("/purchases/${conflict.text("id")}/confirm","POST",proof,owner).statusCode())
    }

    @Test fun `two part public report saves its PDF and enriches all accounts only after completion`() {
        val owner=login();val (_,other)=user()
        val issue=json.read(javaClass.getResourceAsStream("/bnr-issuance.json")!!.bufferedReader().use { it.readText() })
        val pending=bond()+mapOf("bondName" to "October 20-year bond","isin" to "","tenorYears" to 20,"maturityDate" to issue["maturityDate"],"couponRate" to 0,"firstCouponDate" to "","couponDates" to emptyList<String>())
        val a=data(call("/purchases","POST",pending,owner))["purchase"].record()
        val b=data(call("/purchases","POST",pending,other))["purchase"].record()
        // Match the supplied public report's size; optionally exercise the original bytes locally.
        val bytes=System.getenv("GABO_TEST_ISSUANCE_PDF")?.let { java.nio.file.Files.readAllBytes(java.nio.file.Path.of(it)) }
            ?: ByteArray(538039) { 32 }.also { "%PDF-1.7\n".toByteArray().copyInto(it);"\n%%EOF".toByteArray().copyInto(it,it.size-6) }
        assertTrue(bytes.size>512*1024 && bytes.size<=1024*1024)
        val metadata=mapOf("label" to "Bond issuance results","category" to "Bond issuance","documentDate" to issue["issuanceDate"],"instrumentName" to issue["bondName"],"issuer" to issue["issuer"],"issuance" to issue)
        fun part(index: Int,uploadId: String="b".repeat(32),cookie: String=owner,details: Record=metadata)=call("/documents","POST",mapOf("uploadId" to uploadId,"index" to index,"count" to 2,
            "content" to Base64.getEncoder().encodeToString(bytes.copyOfRange(index*512*1024,minOf(bytes.size,(index+1)*512*1024))),"metadata" to details),cookie)
        val first=part(0);assertEquals(200,first.statusCode(),first.body());assertFalse(data(first).containsKey("documentId"))
        assertEquals(0,db.queryForObject("SELECT count(*) FROM bond_issuance_documents",Int::class.java))
        assertEquals(409,part(1,details=metadata+mapOf("label" to "Changed during upload")).statusCode())
        val complete=part(1);assertEquals(200,complete.statusCode(),complete.body());assertEquals(2,data(complete)["updatedPendingPurchases"])
        for((purchase,cookie) in listOf(a to owner,b to other)) {
            val saved=data(call("/purchases/${purchase.text("id")}",cookie=cookie))["purchase"].record()
            assertEquals("submitted",saved["status"]);assertEquals(.1315,saved.number("couponRate"));assertEquals(40,saved.strings("couponDates").size)
            assertEquals(purchase.number("faceValue"),saved.number("faceValue"));assertEquals(11000.0,saved.number("feesPaid"))
            val document=data(call("/documents",cookie=cookie)).records("documents").single()
            assertEquals(data(complete)["documentId"],document["id"])
        }
        val download=http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/bonds/documents/${data(complete).text("documentId")}"))
            .header("Cookie",other).GET().build(),HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200,download.statusCode());assertArrayEquals(bytes,download.body())
        assertEquals(200,part(0,"c".repeat(32),other).statusCode())
        val duplicate=part(1,"c".repeat(32),other);assertEquals(200,duplicate.statusCode(),duplicate.body());assertEquals(true,data(duplicate)["duplicate"])
        assertEquals(1,db.queryForObject("SELECT count(*) FROM bond_issuance_documents",Int::class.java))
        assertEquals(0,db.queryForObject("SELECT count(*) FROM bond_uploaded_documents",Int::class.java))
    }

    @Test fun `issuance uploads are shared idempotent and guarded against conflicting terms`() {
        val cookie=login()
        val issue=json.read(javaClass.getResourceAsStream("/bnr-issuance.json")!!.bufferedReader().use { it.readText() })
        fun send(id: Char, terms: Record=issue, token: String=cookie)=call("/documents","POST",mapOf(
            "uploadId" to id.toString().repeat(32),"count" to 1,"index" to 0,
            "content" to Base64.getEncoder().encodeToString("%PDF-1.7 synthetic $id".toByteArray()),
            "metadata" to mapOf("category" to "Bond issuance","label" to "Bond issuance results","instrumentName" to issue["bondName"],"issuer" to "Government of Rwanda","documentDate" to "2026-10-21","issuance" to terms)),token)
        // Different bytes and concurrent sessions still leave a single canonical report.
        val (_,concurrentUser)=user()
        val gate=java.util.concurrent.CountDownLatch(1)
        val first=java.util.concurrent.CompletableFuture.supplyAsync { gate.await();send('a') }
        val second=java.util.concurrent.CompletableFuture.supplyAsync { gate.await();send('b',token=concurrentUser) }
        gate.countDown()
        val a=first.get();val b=second.get()
        assertEquals(200,a.statusCode(),a.body());assertEquals(200,b.statusCode(),b.body())
        assertEquals(data(a)["documentId"],data(b)["documentId"])
        assertEquals(1,db.queryForObject("SELECT count(*) FROM bond_issuance_documents",Int::class.java))
        assertEquals(0,db.queryForObject("SELECT count(*) FROM bond_document_uploads",Int::class.java))
        assertEquals(0,db.queryForObject("SELECT count(*) FROM bond_purchases",Int::class.java))
        val docs=data(call("/documents",cookie=cookie)).records("documents")
        val saved=docs.single()["issuance"].record()
        assertEquals("RWBO20Y000686/MN",saved["isin"])
        assertEquals(40,saved.strings("couponDates").size)
        assertEquals(409,send('c',issue+mapOf("couponRate" to .15)).statusCode())
        assertEquals(1,db.queryForObject("SELECT count(*) FROM bond_issuance_documents",Int::class.java))
        assertEquals(200,send('d',issue+mapOf("settlementDate" to "2026-11-23")).statusCode()) // reopening
        val (_,other)=user()
        assertEquals(2,data(call("/documents",cookie=other)).records("documents").size)
        val otherUpload=send('e',token=other)
        assertEquals(200,otherUpload.statusCode(),otherUpload.body())
        assertEquals(data(a)["documentId"],data(otherUpload)["documentId"])
        assertEquals(true,data(otherUpload)["duplicate"])
        assertEquals(200,call("/documents/${data(a)["documentId"]}",cookie=other).statusCode())
        assertEquals(401,call("/documents/${data(a)["documentId"]}").statusCode())
        assertEquals(2,db.queryForObject("SELECT count(*) FROM bond_issuance_documents",Int::class.java))
        assertEquals(400,send('f',issue+mapOf("couponDates" to listOf("2047-01-01"))).statusCode())
        assertEquals(400,send('f',issue+mapOf("amountSold" to -1)).statusCode())
    }
}
