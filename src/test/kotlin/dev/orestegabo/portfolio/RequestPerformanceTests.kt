package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.jdbc.datasource.AbstractDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement

class RequestPerformanceTests {
    @Test fun `correlation identifiers and route labels cannot leak supplied content`() {
        assertEquals("abc-12345", RequestPerformance.requestId("abc-12345"))
        assertNotEquals("secret\nvalue", RequestPerformance.requestId("secret\nvalue"))
        assertEquals("/api/bonds/other", RequestPerformance.route("/api/bonds/documents/private-user-id"))
        assertEquals("/api/bonds/auth/session", RequestPerformance.route("/api/bonds/auth/session"))
    }
    @Test fun `filter preserves correlation status and clears failed request timings`() {
        val filter = RequestPerformanceFilter(true)
        val request = MockHttpServletRequest("GET", "/api/bonds/auth/session")
        request.addHeader("X-Request-ID", "test-12345")
        val response = MockHttpServletResponse()
        assertThrows(IllegalStateException::class.java) {
            filter.doFilter(request, response) { _, _ ->
                RequestPerformance.measure("session_validation") { throw IllegalStateException("synthetic") }
            }
        }
        assertEquals("test-12345", response.getHeader("X-Request-ID"))
        assertTrue(RequestPerformance.finish().isEmpty())
    }
    @Test fun `optional datasource measures acquisition and execution without altering results`() {
        val statement = Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
            when(method.name) { "executeUpdate" -> 7; "close" -> null; else -> throw UnsupportedOperationException(method.name) }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when(method.name) { "prepareStatement" -> statement; "close" -> null; else -> throw UnsupportedOperationException(method.name) }
        } as Connection
        val source = TimedDataSource(object : AbstractDataSource() {
            override fun getConnection() = connection
            override fun getConnection(username: String, password: String) = connection
        })
        RequestPerformance.start()
        val result = source.connection.use { it.prepareStatement("synthetic - never logged").use { stmt -> stmt.executeUpdate() } }
        val stages = RequestPerformance.finish()
        assertEquals(7, result)
        assertEquals(setOf("db_connection", "db_execute"), stages.keys)
        assertTrue(stages.values.all { it >= 0 })
    }
}
