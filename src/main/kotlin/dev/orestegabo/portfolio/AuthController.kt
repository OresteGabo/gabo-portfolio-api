package dev.orestegabo.portfolio

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/bonds/auth")
class AuthController(private val auth: AuthService, private val portfolios: PortfolioService,private val notifications: PriceNotifications) {
    @GetMapping("/session") fun session(request: HttpServletRequest): Record {
        val account = auth.session(request)
        return mapOf("authenticated" to (account != null), "user" to account?.publicInfo())
    }
    @PostMapping("/login") fun login(@RequestBody body: Record, request: HttpServletRequest, response: HttpServletResponse): Record {
        val email = Passwords.email(body["email"])
        auth.rateLimit(request, "login", email ?: "invalid")
        checkApi(email != null && body["password"] is String && body.text("password").length <= 128, "Invalid email or password.", 401)
        val account = auth.authenticate(email!!, body.text("password")) ?: throw ApiError(401, "Invalid email or password.")
        auth.start(account, request, response)
        return mapOf("authenticated" to true, "user" to account.publicInfo())
    }
    @PostMapping("/logout") fun logout(request: HttpServletRequest, response: HttpServletResponse, @RequestBody(required=false) body: Record?): Record {
        val device=body?.text("deviceId").orEmpty()
        if(device.isNotBlank())notifications.unregister(currentAccount().id,device)
        auth.revoke(request); auth.clear(response)
        return mapOf("authenticated" to false)
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody body: Record, request: HttpServletRequest, response: HttpServletResponse): Record {
        val email = Passwords.email(body["email"])
        auth.rateLimit(request, "register", email ?: "invalid")
        checkApi(email != null && body["password"] is String && body.text("password").length in 15..128, "Use a valid email and a password between 15 and 128 characters.")
        checkApi(Regex("[A-Za-z0-9_-]{43}").matches(body.text("invite")), "This invitation is invalid or expired.", 403)
        val account = auth.register(email!!, body.text("password"), body.text("invite"))
        auth.start(account, request, response)
        return mapOf("authenticated" to true, "user" to account.publicInfo())
    }
    @GetMapping("/registration") fun registration(): Record {
        currentOwner()
        return mapOf("available" to true, "invitation" to auth.invitation(), "passwordSetupPath" to "/create-password", "friendEmail" to auth.friend()?.email)
    }
    @PostMapping("/registration") @ResponseStatus(HttpStatus.CREATED)
    fun invite(@RequestBody body: Record): Record {
        currentOwner()
        val email = if (body.text("email").isBlank()) "" else Passwords.email(body["email"]) ?: throw ApiError(400, "Use a valid email address.")
        checkApi(email.isEmpty() || auth.byEmail(email) == null, "This email already has access.")
        return auth.invite(email)
    }
    @DeleteMapping("/registration") fun close(): Record { currentOwner(); auth.closeInvites(); return mapOf("closed" to true) }
    @GetMapping("/accounts") fun accounts(): Record {
        val owner = currentOwner()
        return mapOf("accounts" to portfolios.accounts(owner, auth.friend()))
    }
    @DeleteMapping("/accounts/{id}") fun remove(@PathVariable id: String, @RequestBody body: Record): Record {
        currentOwner()
        val email = Passwords.email(body["email"]) ?: throw ApiError(400, "Confirm the account email address.")
        checkApi(auth.delete(id, email), "Account not found, or the confirmation email does not match.", 404)
        return mapOf("deleted" to true)
    }
    @DeleteMapping("/account") fun delete(@RequestBody body: Record, request: HttpServletRequest, response: HttpServletResponse): Record {
        val account = currentAccount()
        checkApi(!account.owner && account.id != auth.friend()?.id && uuid(account.id) && account.id[14] == '4', "Only invited accounts can be deleted here.", 403)
        auth.rateLimit(request, "account-deletion", account.id)
        checkApi(body.text("password").isNotEmpty() && Passwords.verify(body.text("password"), account.passwordHash), "The password is incorrect. Your account has not been deleted.", 403)
        checkApi(auth.delete(account.id, account.email), "Account deletion was not confirmed. Please try again.", 409)
        auth.clear(response)
        return mapOf("deleted" to true)
    }
}
