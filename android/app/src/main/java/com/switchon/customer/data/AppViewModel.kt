package com.switchon.customer.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.switchon.customer.data.backend.RepairBackend
import com.switchon.customer.data.diagnostics.DiagnosticReport
import com.switchon.customer.data.diagnostics.MemorySavedReports
import com.switchon.customer.data.diagnostics.SavedReports
import com.switchon.customer.data.backend.claimBlocker as sharedClaimBlocker
import com.switchon.customer.data.remote.SessionStore
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.UUID

class OfflineException : Exception("You're offline. Check your connection and try again.")
class ServerException : Exception("Something went wrong on our side. Nothing was changed. Please try again.")
/** A second tap while the same action is still running. Screens ignore it. */
class BusyException : Exception("Already working on it.")
/** The action no longer applies, e.g. a quote that was already decided. Message is customer-facing. */
class NotAllowedException(message: String) : Exception(message)
/** Couldn't send now, so it's saved and will go out automatically once the phone is back online. */
class QueuedException(message: String) : Exception(message)

data class BookingDraft(
    val device: DeviceType? = null,
    val issue: IssueType? = null,
    val brand: String? = null,
    val model: String? = null,
    val symptoms: Set<String> = emptySet(),
    val notes: String = "",
    /** Content URIs from the photo picker, kept here so they survive moving between steps. */
    val photoUris: List<String> = emptyList(),
    val addressId: String? = null,
    val slot: Slot? = null,
    val contactName: String = "",
    /** Ten digits, no country code. */
    val contactPhone: String = "",
    /** Sent with the booking; the server de-duplicates on it, so retries and the offline queue can't double-book. */
    val requestId: String = UUID.randomUUID().toString(),
    /** Preliminary device check run before booking, if the customer ran one. Shown at review; never changes the price. */
    val diagnosticReport: DiagnosticReport? = null,
    /** The device-check offer was shown (and run or declined), so booking doesn't ask again. */
    val checkOffered: Boolean = false,
    /** The issue was pre-selected from the device check's suggestion, not chosen by the customer. */
    val issueFromCheck: Boolean = false,
) {
    val photos get() = photoUris.size
    val estimate get() = if (device != null && issue != null) Catalog.estimate(device, issue, brand) else null
}

/**
 * App state for the screens. Data comes from [backend] (the API in prod, sample data in the demo build). Every
 * action goes through [call], which refuses a second copy of an action that's still running and honours the
 * prototype's "simulate offline" and "fail next request" switches.
 */
class AppViewModel(
    private val backend: RepairBackend,
    private val network: NetworkMonitor,
    private val session: SessionStore,
    private val queue: ActionQueue? = null,
    private val latency: Boolean = backend.isDemo,
    /** Device-check reports attached to bookings, kept on this phone (the API has no field for them). */
    private val reports: SavedReports = MemorySavedReports(),
) : ViewModel() {

    val isDemo get() = backend.isDemo
    val canAdvance get() = backend.canAdvance
    /** Bookings and support reports made offline can be saved and sent later (prod build only). */
    val canQueue get() = queue != null

    // ---- Session -------------------------------------------------------------------------------------
    var signedIn by mutableStateOf(session.entered); private set
    /** After the first visit (or a logout) the splash is skipped and onboarding opens directly. */
    var introSeen by mutableStateOf(session.entered); private set
    var profile by mutableStateOf(if (backend.isDemo) MockData.profile else Profile("", "", "")); private set

    // ---- Data ----------------------------------------------------------------------------------------
    val repairs = mutableStateListOf<Repair>().apply { if (backend.isDemo) addAll(MockData.repairs()) }
    val addresses = mutableStateListOf<Address>().apply { if (backend.isDemo) addAll(listOf(MockData.home, MockData.work)) }
    val tickets = mutableStateListOf<Ticket>()
    val pending = mutableStateListOf<PendingItem>()
    val chat = mutableStateListOf(
        ChatMessage(false, "Hi, I'm Neha from Switch On support. How can I help today?", nowIst().minusMinutes(1)),
    )
    var notificationPrefs by mutableStateOf(NotificationPrefs())
    var privacyPrefs by mutableStateOf(PrivacyPrefs())
    var draft by mutableStateOf(BookingDraft())
    private var slotMap by mutableStateOf<Map<LocalDate, List<Pair<Slot, Boolean>>>>(if (backend.isDemo) com.switchon.customer.data.backend.demoSlots() else emptyMap())

    /** The repairs list has data to show (fresh, or the cached copy when offline). */
    var repairsLoaded by mutableStateOf(false)
    /** What's shown is the cached copy because the last refresh failed. */
    var repairsStale by mutableStateOf(false); private set
    /** Why the last refresh failed, for the screens' error panels. */
    var loadError by mutableStateOf<String?>(null); private set

    // ---- Network -------------------------------------------------------------------------------------
    val deviceOnline get() = network.online
    var simulateOffline by mutableStateOf(false)
    var failNextRequest by mutableStateOf(false)
    val offline get() = simulateOffline || !deviceOnline

    /** Keys of actions currently running, e.g. "pay:SO-482917". */
    private val inFlight = mutableSetOf<String>()
    /** Idempotency keys reused across manual retries of the same action until it succeeds. */
    private val payKeys = mutableMapOf<String, String>()
    private val claimKeys = mutableMapOf<String, String>()
    private val ticketKeys = mutableMapOf<String, String>()

    override fun onCleared() = network.close()

    private suspend fun <T> call(key: String, ms: Long = 900, block: suspend () -> T): Result<T> {
        if (!inFlight.add(key)) return Result.failure(BusyException())
        try {
            if (latency) delay(ms)
            if (offline) return Result.failure(OfflineException())
            if (failNextRequest) {
                failNextRequest = false
                return Result.failure(ServerException())
            }
            return try {
                Result.success(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        } finally {
            inFlight.remove(key)
        }
    }

    fun isBusy(key: String) = key in inFlight

    // ---- Session entry -------------------------------------------------------------------------------

    /**
     * Guest access. Real OTP authentication can replace this without changing the customer flow: on the server,
     * bookings and history hang off the customer record, which a login can later merge into.
     */
    fun continueAsGuest() {
        introSeen = true
        signedIn = true
        session.entered = true
    }

    /** Back to guest onboarding. The guest identity (and so the repair history) stays on this device. */
    fun logout() {
        signedIn = false
        introSeen = true
        session.entered = false
        draft = BookingDraft()
        repairsLoaded = false
    }

    /** Loads everything for a signed-in guest: the cached copy first, then fresh data from the backend. */
    suspend fun open() {
        if (!backend.isDemo) {
            val cached = runCatching { backend.cachedRepairs() }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) { setRepairs(cached); repairsLoaded = true; repairsStale = true }
            runCatching { backend.cachedAddresses() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { setAddresses(it) }
        }
        // Not through call(): the backend falls back to the cached profile and catalogue when it can't connect.
        if (!simulateOffline) try {
            profile = backend.open()
            loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.friendly()
        }
        refreshRepairs()
        refreshAddresses()
        refreshSlots()
        refreshPending()
    }

    suspend fun refreshRepairs(): Result<Unit> = call("refresh", 900) { backend.repairs() }
        .onSuccess { setRepairs(it); repairsLoaded = true; repairsStale = false; loadError = null }
        .onFailure { e -> if (e !is BusyException) { loadError = e.friendly(); if (repairs.isNotEmpty()) { repairsLoaded = true; repairsStale = true } } }
        .map { }

    suspend fun refreshRepair(id: String): Result<Repair> = call("repair:$id", 300) { backend.repair(id) }.onSuccess(::put)

    private suspend fun refreshAddresses() = call("addresses", 0) { backend.addresses() }.onSuccess(::setAddresses)

    suspend fun refreshSlots(): Result<Unit> = call("slots", 0) { backend.slots() }.onSuccess { slotMap = it }.map { }

    suspend fun refreshPending() { queue?.let { q -> runCatching { q.pending() }.onSuccess { pending.clear(); pending.addAll(it) } } }

    suspend fun dismissPending(requestId: String) { queue?.dismiss(requestId); refreshPending() }

    private fun setRepairs(list: List<Repair>) { repairs.clear(); repairs.addAll(list) }
    private fun setAddresses(list: List<Address>) { addresses.clear(); addresses.addAll(list) }

    private fun put(r: Repair) {
        val i = repairs.indexOfFirst { it.id == r.id }
        if (i >= 0) repairs[i] = r else repairs.add(0, r)
    }

    private fun Throwable.friendly(): String = message ?: "Something went wrong. Please try again."

    // ---- Queries -------------------------------------------------------------------------------------

    fun repair(id: String?): Repair? = repairs.firstOrNull { it.id == id }
    val active get() = repairs.filter { it.isActive }.sortedByDescending { it.createdAt }
    val completed get() = repairs.filter { it.isDelivered }.sortedByDescending { it.deliveredAt }
    val cancelled get() = repairs.filter { it.isCancelled }.sortedByDescending { it.cancellation?.at }
    val defaultAddress get() = addresses.firstOrNull { it.isDefault } ?: addresses.firstOrNull()

    // ---- Booking -------------------------------------------------------------------------------------

    /**
     * Attaches a finished device check to the booking. Inside the booking flow it keeps the customer's choices and
     * only fills the issue if none was picked; from Home it starts a fresh booking for the checked device. A suggested
     * issue is used only if that repair is offered for the device, and it stays editable.
     */
    fun attachDiagnosticReport(report: DiagnosticReport, fromBooking: Boolean) {
        if (!fromBooking) startBooking()
        val d = draft
        val device = d.device ?: report.device.deviceType
        val suggested = report.suggestedIssue?.issue?.takeIf { Catalog.basePrice(device, it) != null }
        val issue = d.issue ?: suggested
        draft = d.copy(
            device = device,
            issue = issue,
            symptoms = if (d.device != device || d.issue != issue) emptySet() else d.symptoms,
            diagnosticReport = report,
            checkOffered = true,
            issueFromCheck = d.issue == null && issue != null,
        )
    }

    /** The device check attached when this repair was booked on this phone, if any. */
    fun savedReport(repairId: String): DiagnosticReport? = reports.forRepair(repairId)

    fun declineDiagnosticCheck() { draft = draft.copy(checkOffered = true) }

    /** Picks the device. A different device gets its own check offer; a report from another device no longer applies. */
    fun chooseDevice(device: DeviceType) {
        if (draft.device == device) return
        draft = draft.copy(
            device = device, issue = null, brand = null, model = null, symptoms = emptySet(),
            checkOffered = false, diagnosticReport = null, issueFromCheck = false,
        )
    }

    fun startBooking(device: DeviceType? = null, issue: IssueType? = null) {
        draft = BookingDraft(
            device = device, issue = issue, addressId = defaultAddress?.id,
            contactName = profile.name, contactPhone = profile.phoneDigits,
        )
    }

    /** The first problem with the pickup step, or null when it's ready. */
    fun pickupProblem(d: BookingDraft = draft): String? {
        val address = addresses.firstOrNull { it.id == d.addressId }
        return when {
            address == null -> "Choose a pickup address."
            !Catalog.isServiceable(address.pincode) -> "We don't pick up from ${address.pincode} yet. Choose another address."
            d.slot == null -> "Choose a two-hour pickup window."
            else -> Validation.name(d.contactName) ?: Validation.mobile(d.contactPhone)
        }
    }

    /** Everything the review step needs; null when the draft can be booked. */
    fun bookingProblem(d: BookingDraft = draft): String? = when {
        d.device == null -> "Choose the device."
        d.issue == null || d.estimate == null -> "Choose what's wrong with it."
        Validation.notes(d.notes) != null -> Validation.notes(d.notes)
        else -> pickupProblem(d)
    }

    /**
     * Books the pickup. Offline in the prod build, the booking is queued (same request id) and sent automatically
     * later; that comes back as a [QueuedException] so the screen can say so.
     */
    suspend fun confirmBooking(): Result<String> {
        val d = draft
        bookingProblem(d)?.let { return Result.failure(NotAllowedException(it)) }
        // Saved first, so a booking that ends up in the offline queue keeps its report too.
        d.diagnosticReport?.let { reports.save(d.requestId, it) }
        val result = call("book", 1400) { backend.book(d) }
        result.onSuccess { put(it); reports.link(d.requestId, it.id); draft = BookingDraft() }
        val q = queue
        if (q != null && result.exceptionOrNull() is OfflineException) {
            q.queueBooking(d)
            draft = BookingDraft()
            refreshPending()
            return Result.failure(QueuedException("You're offline, so we've saved this booking. It will be sent as soon as you're back online."))
        }
        return result.map { it.id }
    }

    suspend fun cancel(id: String, reason: String): Result<Unit> = call("cancel:$id") { backend.cancel(id, reason) }.onSuccess(::put).map { }

    suspend fun reschedule(id: String, slot: Slot): Result<Unit> = call("reschedule:$id") { backend.reschedule(id, slot) }.onSuccess(::put).map { }

    // ---- Quote (never queued or retried automatically) ---------------------------------------------

    suspend fun approveQuote(id: String): Result<Unit> = call("quote:$id", 1200) { backend.decideQuote(id, true, null) }.onSuccess(::put).map { }

    suspend fun declineQuote(id: String, reason: String): Result<Unit> = call("quote:$id", 1200) { backend.decideQuote(id, false, reason) }.onSuccess(::put).map { }

    // ---- Tracking ------------------------------------------------------------------------------------

    /** Development tool: move an order one step, as the partner or shop would. */
    suspend fun advance(id: String): Result<Repair> = call("advance:$id", 0) { backend.advance(id) }.onSuccess(::put)

    // ---- Payment (never queued or retried automatically) ---------------------------------------------

    /** [upiId] is required for UPI; [cashConfirmed] for cash. Card is handed to the (simulated) card page. */
    suspend fun pay(id: String, method: PayMethod, upiId: String = "", cashConfirmed: Boolean = false): Result<Unit> {
        val key = payKeys.getOrPut(id) { UUID.randomUUID().toString() }
        return call("pay:$id", 1500) { backend.pay(id, method, upiId, cashConfirmed, key) }
            .onSuccess { put(it); payKeys.remove(id) }
            .map { }
    }

    // ---- Warranty ------------------------------------------------------------------------------------

    /** Why a repair can't take a warranty claim, or null when it can. The server applies the same rules. */
    fun claimBlocker(r: Repair, today: LocalDate = todayIst()): String? = sharedClaimBlocker(r, repairs, today)

    suspend fun raiseClaim(originalId: String, problem: String, detail: String, slot: Slot, addressId: String): Result<String> {
        val key = claimKeys.getOrPut(originalId) { UUID.randomUUID().toString() }
        return call("claim:$originalId", 1400) { backend.raiseClaim(originalId, problem, detail, slot, addressId, key) }
            .onSuccess { put(it); claimKeys.remove(originalId); runCatching { refreshRepair(originalId) } }
            .map { it.id }
    }

    // ---- Support -------------------------------------------------------------------------------------

    /** Offline in the prod build, the ticket is queued and sent later ([QueuedException]). */
    suspend fun reportIssue(repairId: String?, topic: String, detail: String): Result<String> {
        val fingerprint = "$repairId|$topic|${detail.trim()}"
        val key = ticketKeys.getOrPut(fingerprint) { UUID.randomUUID().toString() }
        val result = call("report") { backend.reportIssue(repairId, topic, detail, key) }
        result.onSuccess { tickets.add(0, it); ticketKeys.remove(fingerprint) }
        val q = queue
        if (q != null && result.exceptionOrNull() is OfflineException) {
            q.queueTicket(repairId, topic, detail, key)
            ticketKeys.remove(fingerprint)
            refreshPending()
            return Result.failure(QueuedException("You're offline, so we've saved your report. It will be sent as soon as you're back online."))
        }
        return result.map { it.id }
    }

    /** Support chat is a scripted assistant in this phase; it isn't sent to the server. */
    suspend fun sendChat(text: String): Result<Unit> {
        if (isBusy("chat")) return Result.failure(BusyException())
        chat.add(ChatMessage(true, text.trim(), nowIst()))
        return call("chat", 1600) {
            val r = active.firstOrNull()
            val q = r?.quote
            val reply = when {
                (text.contains("quote", true) || text.contains("price", true)) && r != null && q != null ->
                    "Your quote for ${r.id} is ${rupees(q.total)}, ${estimateVerdict(r, q.total)} (${rupeeRange(r.estimateLow, r.estimateHigh)}). " +
                        if (q.decision == null) "Nothing starts until you approve it in the app." else "You've already ${if (q.decision == Decision.APPROVED) "approved" else "declined"} it."
                text.contains("quote", true) || text.contains("price", true) ->
                    "There's no quote waiting right now. The shop sends one after diagnosis, and nothing is repaired until you approve it."
                text.contains("refund", true) || text.contains("pay", true) ->
                    "You only pay after the device is back with you and working, by UPI, card or cash. Nothing is charged before that."
                (text.contains("where", true) || text.contains("status", true) || text.contains("track", true)) && r != null ->
                    "${r.id} is at step ${r.stage.ordinal + 1} of 10: ${r.stage.label.lowercase()}" +
                        (r.shop?.let { ", with ${it.name} in ${it.area.substringAfterLast(", ")}" } ?: "") +
                        (r.sealCode?.let { ". Seal $it was intact at the last handover." } ?: ".")
                text.contains("where", true) || text.contains("status", true) || text.contains("track", true) ->
                    "You don't have a repair in progress. Book one from Home and you can track every step here."
                else -> "Thanks, noted. I've added this to your repair file. Anything else I can check for you?"
            }
            chat.add(ChatMessage(false, reply, nowIst()))
        }
    }

    // ---- Addresses -----------------------------------------------------------------------------------

    suspend fun saveAddress(a: Address): Result<Address> {
        (Validation.required(a.line1, "Flat or house") ?: Validation.required(a.area, "The area") ?: Validation.required(a.city, "The city")
            ?: Validation.pincode(a.pincode))?.let { return Result.failure(NotAllowedException(it)) }
        return call("address", 700) { backend.saveAddress(a) to backend.addresses() }
            .onSuccess { (_, all) -> setAddresses(all) }
            .map { it.first }
    }

    suspend fun deleteAddress(id: String): Result<Unit> =
        call("address:$id", 300) { backend.deleteAddress(id); backend.addresses() }.onSuccess(::setAddresses).map { }

    suspend fun makeDefault(id: String): Result<Unit> =
        call("address:$id", 300) { backend.makeDefaultAddress(id); backend.addresses() }.onSuccess(::setAddresses).map { }

    // ---- Prototype controls --------------------------------------------------------------------------

    fun resetDemo(empty: Boolean) {
        if (!backend.isDemo) return
        backend.resetDemo(empty)
        repairs.clear()
        if (!empty) repairs.addAll(MockData.repairs())
        setAddresses(listOf(MockData.home, MockData.work))
        tickets.clear()
        repairsLoaded = false
        draft = BookingDraft()
        simulateOffline = false
        failNextRequest = false
    }

    /** Pickup windows by day, with whether each still has room. */
    fun slots(): Map<LocalDate, List<Pair<Slot, Boolean>>> = slotMap
}

fun estimateVerdict(r: Repair, total: Int) = when {
    r.warrantyClaimFor != null -> "covered by warranty"
    total < r.estimateLow -> "below your estimate"
    total <= r.estimateHigh -> "within your estimate"
    else -> "${rupees(total - r.estimateHigh)} above your estimate"
}
