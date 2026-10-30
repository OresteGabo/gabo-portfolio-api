package dev.orestegabo.portfolio

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource

/** Fixed labels and aggregate durations only: no SQL, arguments, URLs, users, or credentials. */
object RequestPerformance {
    private val active = ThreadLocal<MutableMap<String, Long>>()
    fun start() { active.set(linkedMapOf()) }
    fun finish(): Map<String, Long> = active.get().orEmpty().toMap().also { active.remove() }
    fun <T> measure(stage: String, block: () -> T): T {
        val timings = active.get() ?: return block()
        val start = System.nanoTime()
        try { return block() }
        finally { timings[stage] = (timings[stage] ?: 0) + System.nanoTime() - start }
    }
    fun requestId(supplied: String?): String = supplied?.takeIf { Regex("[a-zA-Z0-9-]{8,64}").matches(it) } ?: UUID.randomUUID().toString()
    fun route(path: String): String = when(path) {
        "/api/bonds/auth/session", "/api/bonds/auth/login", "/api/bonds/mobile-overview",
        "/api/bonds/purchases", "/api/bonds/equities", "/api/bonds/documents", "/api/bonds/statement-items",
        "/api/bonds/notifications", "/api/bonds/notifications/preferences", "/api/bonds/notifications/coupons",
        "/api/bonds/rse/quotes" -> path
        else -> "/api/bonds/other"
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestPerformanceFilter(@Value("\${gabo.performance-diagnostics:false}") private val enabled: Boolean) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/bonds/")
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = RequestPerformance.requestId(request.getHeader("X-Request-ID"))
        response.setHeader("X-Request-ID", id)
        if (!enabled) { chain.doFilter(request, response); return }
        val start = System.nanoTime()
        RequestPerformance.start()
        try { chain.doFilter(request, response) }
        finally {
            val stages = RequestPerformance.finish().mapValues { (_, nanos) -> nanos / 1_000_000.0 }
            log.info("[UnlockPerformance] request_id={} route={} status={} duration_ms={} stages_ms={}",
                id, RequestPerformance.route(request.requestURI), response.status, (System.nanoTime()-start)/1_000_000.0, stages)
        }
    }
}

/** Disabled by default; times pool acquisition and JDBC execution without recording financial SQL. */
@Component
@ConditionalOnProperty(name=["gabo.performance-diagnostics"], havingValue="true")
class DiagnosticDataSources : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
        if (bean is DataSource) TimedDataSource(bean) else bean
}

internal class TimedDataSource(source: DataSource) : DelegatingDataSource(source) {
    override fun getConnection(): Connection = wrap(RequestPerformance.measure("db_connection") { super.getConnection() })
    override fun getConnection(username: String, password: String): Connection =
        wrap(RequestPerformance.measure("db_connection") { super.getConnection(username, password) })
    private fun wrap(connection: Connection): Connection = proxy(Connection::class.java, connection) { method, result ->
        if (method in setOf("prepareStatement", "prepareCall", "createStatement") && result is Statement) {
            // Preserve the JDBC interface expected by the driver caller (including CallableStatement).
            val type = when(result) {
                is java.sql.CallableStatement -> java.sql.CallableStatement::class.java
                is PreparedStatement -> PreparedStatement::class.java
                else -> Statement::class.java
            }
            proxy(type, result) { _, value -> value }
        } else result
    }
    private fun <T> proxy(type: Class<T>, target: Any, after: (String, Any?) -> Any?): T {
        val value = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            fun invoke(): Any? = try { after(method.name, method.invoke(target, *(args ?: emptyArray()))) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            if (target is Statement && method.name.startsWith("execute")) RequestPerformance.measure("db_execute") { invoke() }
            else invoke()
        }
        return type.cast(value)
    }
}
