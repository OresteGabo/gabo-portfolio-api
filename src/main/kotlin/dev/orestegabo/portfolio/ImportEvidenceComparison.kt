package dev.orestegabo.portfolio

/** Compare the evidence's terms; extraction confidence is metadata, not a financial value.
 * The existing saved record is retained when proof is attached, including its confidence.
 */
internal fun sameFinancialRecord(a: Record, b: Record): Boolean = (a.keys + b.keys)
    .filterNot { it in setOf("notes", "sourceUrl", "accountReference", "scheduleConfidence") }
    .all { key ->
        val x = a[key]
        val y = b[key]
        if (x is Number && y is Number) x.toString().toBigDecimal().compareTo(y.toString().toBigDecimal()) == 0 else x == y
    }
