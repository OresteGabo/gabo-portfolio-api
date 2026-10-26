package dev.orestegabo.portfolio

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI

@Configuration
class SecurityConfig(
    private val auth: AuthService,
    private val json: JsonCodec,
    @Value("\${gabo.allowed-origins}") origins: String,
) {
    private val origins = origins.split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
    @Bean fun security(http: HttpSecurity): SecurityFilterChain {
        val cors = CorsConfiguration().apply {
            allowedOrigins = origins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "Authorization")
            allowCredentials = true
            maxAge = 3600
        }
        val source = UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", cors) }
        http.cors { it.configurationSource(source) }
            // Legacy clients use a strict allowlisted Origin and application/json instead of a CSRF token.
            // The filter below enforces this for every mutation, including login and logout.
            .csrf { it.disable() }
            .formLogin { it.disable() }.httpBasic { it.disable() }.logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/api/bonds/auth/session", "/api/bonds/auth/login", "/api/bonds/auth/register", "/api/bonds/rse/**").permitAll()
                    .requestMatchers("/api/bonds/**").authenticated().anyRequest().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> fail(response, 401, "Authentication required.") }
                it.accessDeniedHandler { _, response, _ -> fail(response, 403, "Access denied.") }
            }
            .addFilterBefore(object: OncePerRequestFilter() {
                override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                    if (!request.requestURI.startsWith("/api/")) { chain.doFilter(request, response); return }
                    response.setHeader("Cache-Control", "private, no-store, max-age=0")
                    response.setHeader("Vary", "Origin, Cookie")
                    try {
                        var bounded: HttpServletRequest = request
                        if (request.method in listOf("POST", "PUT", "DELETE")) {
                            val origin = request.getHeader("Origin")
                            checkApi(origin != null && runCatching { URI(origin).let { it.scheme + "://" + it.rawAuthority } }.getOrNull() in origins, "Invalid request origin.", 403)
                            val limit = when {
                                request.requestURI == "/api/bonds/documents" -> 710000
                                request.requestURI == "/api/bonds/statement-items" -> 131072
                                request.requestURI.startsWith("/api/bonds/purchases") -> 32768
                                request.requestURI == "/api/bonds/equities" -> 16384
                                else -> 4096
                            }
                            checkApi(request.contentLengthLong <= limit, "Request body is too large.", 413)
                            val bytes = request.inputStream.readNBytes(limit + 1)
                            checkApi(bytes.size <= limit, "Request body is too large.", 413)
                            if (bytes.isNotEmpty()) checkApi(request.contentType?.substringBefore(';')?.trim()?.equals("application/json", true) == true, "Expected a JSON request body.", 415)
                            bounded = BodyRequest(request, bytes)
                        }
                        auth.session(request)?.let { account ->
                            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(account, null, listOf(SimpleGrantedAuthority(if (account.owner) "ROLE_OWNER" else "ROLE_USER")))
                        }
                        chain.doFilter(bounded, response)
                    } catch(e: ApiError) { fail(response, e.status, e.message) }
                    catch (_: Exception) { fail(response, 503, "The account service is unavailable. Please try again.") }
                    finally { SecurityContextHolder.clearContext() }
                }
            }, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
    private fun fail(response: HttpServletResponse, status: Int, message: String) {
        if (response.isCommitted) return
        response.status = status
        response.contentType = "application/json"
        response.writer.write(json.write(mapOf("error" to message)))
    }
}

private class BodyRequest(request: HttpServletRequest, private val bytes: ByteArray): HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream {
        val stream = ByteArrayInputStream(bytes)
        return object: ServletInputStream() {
            override fun read() = stream.read()
            override fun isFinished() = stream.available() == 0
            override fun isReady() = true
            override fun setReadListener(listener: ReadListener) { throw UnsupportedOperationException() }
        }
    }
    override fun getReader() = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
}

fun currentAccount(): Account = SecurityContextHolder.getContext().authentication?.principal as? Account ?: throw ApiError(401, "Authentication required.")
fun currentOwner(): Account = currentAccount().also { checkApi(it.owner, "Owner access required.", 403) }
