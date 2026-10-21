package com.switchon.customer.data.diagnostics

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * Device-check reports kept on this phone with the bookings they were attached to. The booking API has no field for
 * report data, so reports are not sent to the server: they stay here, keyed by the booking's request id and linked to
 * the repair id once the server has accepted the booking (straight away, or later from the offline queue).
 */
interface SavedReports {
    fun save(requestId: String, report: DiagnosticReport)
    fun link(requestId: String, repairId: String)
    fun forRepair(repairId: String): DiagnosticReport?
}

class MemorySavedReports : SavedReports {
    private val reports = mutableMapOf<String, DiagnosticReport>()
    private val links = mutableMapOf<String, String>()
    override fun save(requestId: String, report: DiagnosticReport) { reports[requestId] = report }
    override fun link(requestId: String, repairId: String) { if (requestId in reports) links[repairId] = requestId }
    override fun forRepair(repairId: String) = links[repairId]?.let(reports::get)
}

/** App-private preferences; excluded from cloud backup with the rest of the app's data. Keeps the latest [LIMIT]. */
class PrefsSavedReports(context: Context) : SavedReports {
    private val prefs = context.applicationContext.getSharedPreferences("device_checks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, DiagnosticReport?>()

    override fun save(requestId: String, report: DiagnosticReport) {
        val order = (prefs.getString(ORDER, "").orEmpty().split(',').filter { it.isNotBlank() && it != requestId } + requestId)
        val dropped = order.dropLast(LIMIT)
        prefs.edit().apply {
            putString("report:$requestId", json.encodeToString(DiagnosticReport.serializer(), report))
            dropped.forEach { remove("report:$it") }
            putString(ORDER, order.takeLast(LIMIT).joinToString(","))
        }.apply()
        cache.clear()
    }

    override fun link(requestId: String, repairId: String) {
        if (prefs.contains("report:$requestId")) prefs.edit().putString("link:$repairId", requestId).apply()
        cache.remove(repairId)
    }

    override fun forRepair(repairId: String): DiagnosticReport? = cache.getOrPut(repairId) {
        val requestId = prefs.getString("link:$repairId", null) ?: return@getOrPut null
        prefs.getString("report:$requestId", null)?.let { runCatching { json.decodeFromString(DiagnosticReport.serializer(), it) }.getOrNull() }
    }

    private companion object {
        const val ORDER = "order"
        const val LIMIT = 20
    }
}
