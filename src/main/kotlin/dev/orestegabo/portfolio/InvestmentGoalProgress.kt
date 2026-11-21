package dev.orestegabo.portfolio

import java.time.LocalDate

/** Same contribution basis as mobile progress, including pending requests, without market gains. */
internal fun goalContributionProgress(bonds: List<Record>, shares: List<Record>, orders: List<Record>,
    preferences: Record, today: LocalDate): Record {
    val yearly = preferences.text("goalPeriod") == "yearly"
    val prefix = if (yearly) today.year.toString() else today.toString().take(7)
    val totals = MobileOverview.totals(bonds, shares, today).filter { it.text("month").startsWith(prefix) }
    val pendingOrders = orders.filter { it.text("status") == "pending" && it.text("currency") == "RWF" &&
        date(it.text("requestDate"))?.let { d -> d <= today && d.toString().startsWith(prefix) } == true }.sumOf { it.decimal("estimatedTotal") }
    return mapOf("goalPeriod" to if(yearly) "yearly" else "monthly","year" to today.year,
        "amount" to preferences.decimal(if(yearly) "yearlyTarget" else "monthlyTarget",if(yearly) "1200000" else "100000"),
        "confirmed" to totals.sumOf { it.decimal("confirmed") },"pending" to totals.sumOf { it.decimal("pending") } + pendingOrders)
}
