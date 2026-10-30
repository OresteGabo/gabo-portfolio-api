package dev.orestegabo.portfolio

import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.ResponseEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

typealias Record = Map<String, Any?>
fun Record.text(key: String, fallback: String = "") = this[key] as? String ?: fallback
fun Record.number(key: String, fallback: Double = 0.0) = (this[key] as? Number)?.toDouble() ?: fallback
fun Record.decimal(key: String, fallback: String = "0"): BigDecimal = (this[key] as? Number)?.toString()?.toBigDecimalOrNull() ?: fallback.toBigDecimal()
@Suppress("UNCHECKED_CAST") fun Any?.record(): Record = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST") fun Record.records(key: String): List<Record> = (this[key] as? List<*>)?.map { it.record() } ?: emptyList()
fun Record.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
fun date(value: String): LocalDate? = if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) runCatching { LocalDate.parse(value) }.getOrNull() else null
fun uuid(value: String): Boolean = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(value)
fun normalized(value: String) = value.uppercase().replace(Regex("[^A-Z0-9]"), "")
class ApiError(val status: Int, override val message: String): RuntimeException(message)
fun checkApi(valid: Boolean, message: String, status: Int = 400) { if (!valid) throw ApiError(status, message) }
fun newId() = UUID.randomUUID().toString()

@Component
class JsonCodec(val mapper: JsonMapper) {
    fun write(value: Any?): String = RequestPerformance.measure("json_encode") { mapper.writeValueAsString(value) }
    fun read(value: String): Record = RequestPerformance.measure("json_decode") { mapper.readValue(value, Map::class.java).record() }
    fun list(value: String): List<Record> = RequestPerformance.measure("json_decode") { mapper.readValue(value, List::class.java).map { it.record() } }
}

@RestControllerAdvice
class ApiErrors {
    private val log = LoggerFactory.getLogger(javaClass)
    @ExceptionHandler(ApiError::class)
    fun known(e: ApiError) = ResponseEntity.status(e.status).body(mapOf("error" to e.message))
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun duplicate(e: DataIntegrityViolationException): ResponseEntity<Record> {
        val cause = generateSequence(e as Throwable) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()
        return if (cause?.sqlState == "23505") ResponseEntity.status(409).body(mapOf("error" to "This purchase or account is already saved."))
        else ResponseEntity.badRequest().body(mapOf("error" to "Check the supplied details and try again."))
    }
    @ExceptionHandler(HttpMessageNotReadableException::class, MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun invalid() = ResponseEntity.badRequest().body(mapOf("error" to "Invalid request."))
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun media() = ResponseEntity.status(415).body(mapOf("error" to "Expected a JSON request body."))
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<Record> {
        // Avoid logging credentials, SQL values, PDF contents or exception text.
        log.error("API operation failed ({})", e.javaClass.simpleName)
        return ResponseEntity.status(503).body(mapOf("error" to "This operation could not be completed. Please try again."))
    }
}
