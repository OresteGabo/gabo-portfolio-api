package dev.orestegabo.portfolio

import org.springframework.stereotype.Service

/** The existing background dispatcher also retries price collection, independently of the website's daily job. */
@Service
class ScheduledPriceNotifications(private val market: MarketService, private val history: MarketHistory,
    private val notifications: PriceNotifications) {
    fun sync(): Record {
        // Reuse the 15-minute quote cache. An unavailable upstream must not block recovery of already saved closes.
        val collection=history.record(market.quotes("equities"))
        val created=notifications.collectRecent()
        return mapOf("status" to collection["status"],"created" to created)
    }
}
