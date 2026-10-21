package com.switchon.customer.data.backend

import com.switchon.customer.data.Address
import com.switchon.customer.data.BookingDraft
import com.switchon.customer.data.PayMethod
import com.switchon.customer.data.Profile
import com.switchon.customer.data.Repair
import com.switchon.customer.data.Slot
import com.switchon.customer.data.Ticket
import java.time.LocalDate

/**
 * Where repair data comes from. The prod build talks to the Switch On API ([com.switchon.customer.data.remote.RemoteBackend]);
 * the demo build runs on in-memory sample data ([MockBackend]).
 *
 * Failures are thrown as [com.switchon.customer.data.OfflineException] (couldn't reach the server),
 * [com.switchon.customer.data.ServerException] (server fault) or [com.switchon.customer.data.NotAllowedException]
 * (the server refused, with a customer-facing reason).
 */
interface RepairBackend {
    val isDemo: Boolean
    /** Whether the development "next update" tool is available. */
    val canAdvance: Boolean

    /** Starts (or resumes) the guest session and refreshes reference data. */
    suspend fun open(): Profile

    /** Last known data, readable offline. */
    suspend fun cachedRepairs(): List<Repair>
    suspend fun cachedAddresses(): List<Address>

    suspend fun repairs(): List<Repair>
    suspend fun repair(id: String): Repair

    suspend fun addresses(): List<Address>
    suspend fun saveAddress(a: Address): Address
    suspend fun deleteAddress(id: String)
    suspend fun makeDefaultAddress(id: String)

    /** Pickup windows by day, with whether each still has room. */
    suspend fun slots(): Map<LocalDate, List<Pair<Slot, Boolean>>>

    /** Books [draft]; replaying the same [BookingDraft.requestId] returns the same repair. */
    suspend fun book(draft: BookingDraft): Repair
    suspend fun cancel(id: String, reason: String): Repair
    suspend fun reschedule(id: String, slot: Slot): Repair
    suspend fun decideQuote(id: String, approve: Boolean, reason: String?): Repair
    /** [idempotencyKey] stays the same across retries of one payment attempt, so it can never be recorded twice. */
    suspend fun pay(id: String, method: PayMethod, upiId: String, cashConfirmed: Boolean, idempotencyKey: String): Repair
    suspend fun raiseClaim(originalId: String, problem: String, detail: String, slot: Slot, addressId: String, requestId: String): Repair
    suspend fun reportIssue(repairId: String?, topic: String, detail: String, requestId: String): Ticket

    /** Development only: move a repair one step, as the partner or shop would. */
    suspend fun advance(id: String): Repair

    /** Demo only: restore or clear the sample data. */
    fun resetDemo(empty: Boolean) {}
}
