package dev.orestegabo.portfolio

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.bouncycastle.crypto.generators.SCrypt
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private val random = SecureRandom()
    fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()
    fun email(value: Any?): String? = (value as? String)?.trim()?.lowercase()?.takeIf { it.length <= 254 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(it) }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun pbkdf(password: String, salt: ByteArray) = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 600000, 256)).encoded
    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256:600000:${salt.toHex()}:${pbkdf(password, salt).toHex()}"
    }
    // Compatible with existing Node scrypt (modern and legacy) and browser PBKDF2 accounts.
    fun verify(password: String, stored: String): Boolean = runCatching {
        if (password.length > 128) return false
        if (stored.startsWith("pbkdf2-sha256:")) {
            val match = Regex("pbkdf2-sha256:600000:([a-fA-F0-9]{32}):([a-fA-F0-9]{64})").matchEntire(stored) ?: return false
            return MessageDigest.isEqual(pbkdf(password, match.groupValues[1].hexToByteArray()), match.groupValues[2].hexToByteArray())
        }
        val modern = stored.startsWith("scrypt:") || stored.startsWith("scrypt$")
        val match = Regex("([a-fA-F0-9]{32})[:$]([a-fA-F0-9]{128})").matchEntire(if (modern) stored.substring(7) else stored) ?: return false
        val actual = SCrypt.generate(password.toByteArray(), match.groupValues[1].toByteArray(), if (modern) 32768 else 16384, 8, if (modern) 3 else 1, 64)
        MessageDigest.isEqual(actual, match.groupValues[2].hexToByteArray())
    }.getOrDefault(false)
}

data class Account(val id: String, val email: String, val passwordHash: String) {
    val owner get() = id == "owner"
    fun publicInfo(): Record = mapOf("id" to id, "email" to email, "isOwner" to owner)
}

@Service
class AuthService(
    private val db: JdbcTemplate,
    @Value("\${gabo.admin-email:}") private val adminEmail: String,
    @Value("\${gabo.admin-password-hash:}") private val adminHash: String,
    @Value("\${gabo.friend-email:}") private val friendEmail: String,
    @Value("\${gabo.friend-password-hash:}") private val friendHash: String,
    @Value("\${gabo.cookie-secure:true}") private val secureCookie: Boolean,
) {
    val cookieName get() = if (secureCookie) "__Host-bonds_session" else "bonds_session_v2"
    fun owner() = Passwords.email(adminEmail)?.takeIf { adminHash.isNotBlank() }?.let { Account("owner", it, adminHash) }
    fun friend(): Account? {
        val email = Passwords.email(friendEmail)?.takeIf { it != owner()?.email && friendHash.isNotBlank() } ?: return null
        val h = Passwords.sha("bonds-environment-friend:$email")
        return Account("${h.substring(0,8)}-${h.substring(8,12)}-8${h.substring(13,16)}-a${h.substring(17,20)}-${h.substring(20,32)}", email, friendHash)
    }
    fun account(id: String): Account? = if (id == "owner") owner() else friend()?.takeIf { it.id == id } ?: db.query("SELECT id,email,password_hash FROM bond_users WHERE id=? AND disabled_at IS NULL", { r, _ -> Account(r.getString(1), r.getString(2), r.getString(3)) }, id).firstOrNull()
    fun byEmail(email: String): Account? = owner()?.takeIf { it.email == email } ?: friend()?.takeIf { it.email == email } ?: db.query("SELECT id,email,password_hash FROM bond_users WHERE email=? AND disabled_at IS NULL", { r, _ -> Account(r.getString(1), r.getString(2), r.getString(3)) }, email).firstOrNull()
    fun authenticate(email: String, password: String): Account? {
        val account = byEmail(email)
        val valid = Passwords.verify(password, account?.passwordHash ?: "scrypt:${"0".repeat(32)}:${"0".repeat(128)}")
        return account?.takeIf { valid }
    }
    fun session(request: HttpServletRequest): Account? {
        val token = request.cookies?.firstOrNull { it.name == cookieName }?.value ?: return null
        if (!Regex("[A-Za-z0-9_-]{43}").matches(token)) return null
        val row = db.queryForList("SELECT user_id,credential_version FROM bond_sessions WHERE token_hash=? AND expires_at>now()", Passwords.sha(token)).firstOrNull() ?: return null
        return account(row["user_id"].toString())?.takeIf { Passwords.sha(it.passwordHash) == row["credential_version"] }
    }
    fun revoke(request: HttpServletRequest) {
        request.cookies?.firstOrNull { it.name == cookieName }?.let { db.update("DELETE FROM bond_sessions WHERE token_hash=?", Passwords.sha(it.value)) }
    }
    fun start(account: Account, request: HttpServletRequest, response: HttpServletResponse) {
        revoke(request)
        val token = Passwords.token()
        db.update("DELETE FROM bond_sessions WHERE expires_at<=now()")
        db.update("INSERT INTO bond_sessions(token_hash,user_id,credential_version,expires_at) VALUES (?,?,?,now()+interval '12 hours')", Passwords.sha(token), account.id, Passwords.sha(account.passwordHash))
        response.addHeader("Set-Cookie", cookie(token, Duration.ofHours(12)))
    }
    fun clear(response: HttpServletResponse) { response.addHeader("Set-Cookie", cookie("", Duration.ZERO)) }
    private fun cookie(token: String, age: Duration) = ResponseCookie.from(cookieName, token).httpOnly(true).secure(secureCookie).sameSite("Strict").path("/").maxAge(age).build().toString()
    fun rateLimit(request: HttpServletRequest, scope: String, identity: String) {
        // Trust forwarding only when the deployed reverse proxy is configured by the operator.
        for ((key, limit) in listOf("$scope:global" to 100, "$scope:identity:$identity" to 5, "$scope:ip:${request.remoteAddr}" to 25)) {
            val count = db.queryForObject("""INSERT INTO bond_auth_attempts(key,count,reset_at) VALUES (?,1,now()+interval '15 minutes')
                ON CONFLICT(key) DO UPDATE SET count=CASE WHEN bond_auth_attempts.reset_at<=now() THEN 1 ELSE bond_auth_attempts.count+1 END,
                reset_at=CASE WHEN bond_auth_attempts.reset_at<=now() THEN EXCLUDED.reset_at ELSE bond_auth_attempts.reset_at END RETURNING count""", Int::class.java, Passwords.sha(key)) ?: 0
            checkApi(count <= limit, "Too many attempts. Try again in 15 minutes.", 429)
        }
    }
    fun invitation(): Record? = db.queryForList("SELECT email,extract(epoch FROM expires_at)*1000 AS \"expiresAt\" FROM bond_registration WHERE id=true AND used_at IS NULL AND expires_at>now()").firstOrNull()
    fun invite(email: String): Record {
        val token = Passwords.token()
        val expiry = System.currentTimeMillis() + Duration.ofHours(48).toMillis()
        db.update("INSERT INTO bond_registration(id,token_hash,email,expires_at) VALUES (true,?,?,to_timestamp(?/1000.0)) ON CONFLICT(id) DO UPDATE SET token_hash=EXCLUDED.token_hash,email=EXCLUDED.email,expires_at=EXCLUDED.expires_at,used_at=NULL", Passwords.sha(token), email, expiry)
        return mapOf("invite" to token, "email" to email, "expiresAt" to expiry)
    }
    fun closeInvites() { db.update("DELETE FROM bond_registration") }
    @Transactional
    fun register(email: String, password: String, token: String): Account {
        checkApi(email != owner()?.email && email != friend()?.email, "This invitation cannot be used.", 403)
        val usable = db.queryForList("SELECT id FROM bond_registration WHERE id=true AND token_hash=? AND (email='' OR email=?) AND used_at IS NULL AND expires_at>now() FOR UPDATE", Passwords.sha(token), email)
        checkApi(usable.isNotEmpty(), "Account creation is closed, or this invitation is invalid or expired.", 403)
        val account = Account(newId(), email, Passwords.hash(password))
        db.update("INSERT INTO bond_users(id,email,password_hash) VALUES (?,?,?)", account.id, account.email, account.passwordHash)
        db.update("UPDATE bond_registration SET used_at=now() WHERE id=true")
        return account
    }
    @Transactional
    fun delete(id: String, email: String): Boolean {
        checkApi(uuid(id) && id != friend()?.id && email != owner()?.email && email != friend()?.email, "Only invited accounts can be deleted here.", 403)
        // Serialize against imports and mutations for this account before deleting all its data.
        lockAccount(db, id)
        if (db.queryForList("SELECT id FROM bond_users WHERE id=? AND email=? FOR UPDATE", id, email).isEmpty()) return false
        for (table in listOf("bond_purchases", "equity_purchases", "bond_statement_items", "bond_uploaded_documents", "bond_document_uploads")) db.update("DELETE FROM $table WHERE owner_id=?", id)
        db.update("DELETE FROM bond_sessions WHERE user_id=?", id)
        db.update("DELETE FROM bond_registration WHERE email=?", email)
        db.update("DELETE FROM bond_users WHERE id=? AND email=?", id, email)
        return true
    }
}

fun lockAccount(db: JdbcTemplate, owner: String) {
    db.execute("SELECT 1")
    db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", { _ -> }, owner)
    // A request authenticated before deletion may not recreate orphan data afterwards.
    if (owner != "owner" && uuid(owner) && owner[14] == '4') checkApi(db.queryForList("SELECT id FROM bond_users WHERE id=? AND disabled_at IS NULL", owner).isNotEmpty(), "Authentication required.", 401)
}
