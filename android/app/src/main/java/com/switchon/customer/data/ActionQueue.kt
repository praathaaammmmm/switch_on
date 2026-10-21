package com.switchon.customer.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/** Something waiting to be sent, shown to the customer so nothing disappears silently. */
data class PendingItem(val requestId: String, val kind: String, val title: String, val createdAt: LocalDateTime, val error: String?)

/**
 * Offline queue for actions that are safe to replay: booking a pickup and sending a support ticket. Each carries the
 * request id the server de-duplicates on. Quote decisions and payments are never queued or retried automatically.
 */
interface ActionQueue {
    suspend fun queueBooking(draft: BookingDraft)
    suspend fun queueTicket(repairId: String?, topic: String, detail: String, requestId: String)
    suspend fun pending(): List<PendingItem>
    /** Removes a refused item the customer has seen. */
    suspend fun dismiss(requestId: String)
    val changes: Flow<Int>
}
