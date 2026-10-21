package com.switchon.customer.data.remote

import com.switchon.customer.data.*
import com.switchon.customer.data.backend.RepairBackend
import com.switchon.customer.data.local.LocalStore
import com.switchon.customer.data.local.PendingAction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate

val apiJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * The prod backend: the Switch On API, with every response written through to [store] so the latest known repairs
 * stay readable offline. [scheduleSync] asks WorkManager to drain the offline queue once there's a connection.
 */
class RemoteBackend(
    private val api: SwitchOnApi,
    private val session: SessionStore,
    private val store: LocalStore,
    private val internalToken: String = "",
    private val scheduleSync: () -> Unit = {},
    /** A queued booking reached the server: request id → repair id (links a device-check report saved on the phone). */
    private val onBookingSent: (requestId: String, repairId: String) -> Unit = { _, _ -> },
) : RepairBackend, ActionQueue {

    override val isDemo = false
    override val canAdvance get() = internalToken.isNotBlank()

    // ---- Errors ----

    /** Runs an API call, translating failures into the exceptions the screens already handle. */
    private suspend fun <T> net(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string()?.let { apiJson.decodeFromString<ErrorBody>(it) } }.getOrNull()
        when (e.code()) {
            in 400..499 -> throw NotAllowedException(body?.error?.message ?: "That couldn't be done. Refresh and try again.")
            else -> throw ServerException()
        }
    } catch (e: IOException) {
        throw OfflineException()
    } catch (e: SerializationException) {
        throw ServerException()
    }

    // ---- Session and reference data ----

    override suspend fun open(): Profile {
        if (session.token == null) {
            val s = net { api.guestSession(GuestSessionRequest(session.installationId)) }
            // A different customer behind this installation (e.g. data was reset): drop the old cache.
            if (session.customerId != null && session.customerId != s.profile.customerId) store.clear()
            session.token = s.token
            session.customerId = s.profile.customerId
            store.put(KEY_PROFILE, apiJson.encodeToString(s.profile))
        }
        if (store.pending().any { it.error == null }) scheduleSync()
        return try {
            val profile = net { api.me() }
            store.put(KEY_PROFILE, apiJson.encodeToString(profile))
            val devices = net { api.devices() }
            val issues = net { api.issues() }
            store.put(KEY_DEVICES, apiJson.encodeToString(devices))
            store.put(KEY_ISSUES, apiJson.encodeToString(issues))
            applyCatalog(devices, issues)
            profile.toDomain()
        } catch (e: OfflineException) {
            // Offline start: carry on with what we know.
            store.get(KEY_DEVICES)?.let { d -> store.get(KEY_ISSUES)?.let { i -> applyCatalog(apiJson.decodeFromString(d), apiJson.decodeFromString(i)) } }
            store.get(KEY_PROFILE)?.let { apiJson.decodeFromString<ProfileDto>(it).toDomain() } ?: throw e
        }
    }

    private fun applyCatalog(devices: DevicesResponse, issues: IssuesResponse) = Catalog.apply(
        devices.categories.associate { c -> c.code to c.brands.map { Triple(it.name, it.priceMultiplier, it.models) } },
        issues.issues.flatMap { i -> i.offerings.map { Catalog.Offering(it.deviceCategory, i.code, it.basePrice, it.turnaround, it.label) } },
        issues.servicePincodePrefixes,
    )

    // ---- Reads (network first, cache as fallback) ----

    override suspend fun cachedRepairs(): List<Repair> =
        store.repairs().mapNotNull { runCatching { apiJson.decodeFromString<RepairDto>(it).toDomain() }.getOrNull() }

    override suspend fun cachedAddresses(): List<Address> =
        store.get(KEY_ADDRESSES)?.let { apiJson.decodeFromString(ListSerializer(AddressDto.serializer()), it).map { a -> a.toDomain() } }.orEmpty()

    override suspend fun repairs(): List<Repair> {
        val list = net { api.repairs() }.repairs
        store.replaceRepairs(list.associate { it.id to apiJson.encodeToString(it) })
        return list.map { it.toDomain() }
    }

    override suspend fun repair(id: String): Repair = save(net { api.repair(id) })

    private suspend fun save(dto: RepairDto): Repair {
        store.putRepair(dto.id, apiJson.encodeToString(dto))
        return dto.toDomain()
    }

    override suspend fun addresses(): List<Address> {
        val list = net { api.addresses() }
        store.put(KEY_ADDRESSES, apiJson.encodeToString(ListSerializer(AddressDto.serializer()), list))
        return list.map { it.toDomain() }
    }

    override suspend fun saveAddress(a: Address): Address {
        val saved = net { api.createAddress(a.toRequest()) }.toDomain()
        runCatching { addresses() }
        return saved
    }

    override suspend fun deleteAddress(id: String) { net { api.deleteAddress(id) }; runCatching { addresses() } }
    override suspend fun makeDefaultAddress(id: String) { net { api.makeDefault(id) }; runCatching { addresses() } }

    override suspend fun slots(): Map<LocalDate, List<Pair<Slot, Boolean>>> =
        net { api.slots() }.slots
            .groupBy { LocalDate.parse(it.date) }
            .mapValues { (_, list) -> list.map { Slot(LocalDate.parse(it.date), it.startHour) to it.available } }
            .toSortedMap()

    // ---- Actions ----

    private fun BookingDraft.toRequest() = CreateRepairRequest(
        clientRequestId = requestId, deviceCategory = device!!.name, issue = issue!!.name, brand = brand, model = model,
        symptoms = symptoms.toList(), notes = notes.trim(), photoCount = photos, addressId = addressId!!,
        slot = SlotRef(slot!!.date.toString(), slot.startHour), contactName = contactName.trim(), contactPhone = contactPhone,
    )

    override suspend fun book(draft: BookingDraft): Repair = save(net { api.createRepair(draft.toRequest()) })
    override suspend fun cancel(id: String, reason: String) = save(net { api.cancel(id, CancelRequest(reason)) })
    override suspend fun reschedule(id: String, slot: Slot) = save(net { api.reschedule(id, RescheduleRequest(SlotRef(slot.date.toString(), slot.startHour))) })
    override suspend fun decideQuote(id: String, approve: Boolean, reason: String?) =
        save(net { api.decideQuote(id, QuoteDecisionRequest(if (approve) "APPROVE" else "DECLINE", reason)) })
    override suspend fun pay(id: String, method: PayMethod, upiId: String, cashConfirmed: Boolean, idempotencyKey: String) =
        save(net { api.pay(id, PaymentRequest(method.name, idempotencyKey, upiId.trim().ifEmpty { null }, cashConfirmed)) })

    override suspend fun raiseClaim(originalId: String, problem: String, detail: String, slot: Slot, addressId: String, requestId: String): Repair {
        val created = net { api.createClaim(WarrantyClaimRequest(requestId, originalId, problem, detail.trim(), addressId, SlotRef(slot.date.toString(), slot.startHour))) }
        runCatching { repair(originalId) } // the original repair gained a "claim opened" event
        return save(created.repair)
    }

    override suspend fun reportIssue(repairId: String?, topic: String, detail: String, requestId: String): Ticket =
        net { api.createTicket(SupportTicketRequest(requestId, repairId, topic, detail.trim())) }.toDomain()

    override suspend fun advance(id: String): Repair {
        if (internalToken.isBlank()) throw NotAllowedException("Live updates come from the repair shop.")
        return save(net { api.internalAdvance(id, internalToken) })
    }

    // ---- Offline queue ----

    override suspend fun queueBooking(draft: BookingDraft) {
        store.addPending(PendingAction(
            draft.requestId, KIND_BOOKING, "${draft.device?.label} · ${draft.issue?.let { Catalog.issueLabel(draft.device!!, it) }} repair",
            apiJson.encodeToString(draft.toRequest()), System.currentTimeMillis(),
        ))
        scheduleSync()
    }

    override suspend fun queueTicket(repairId: String?, topic: String, detail: String, requestId: String) {
        store.addPending(PendingAction(
            requestId, KIND_TICKET, "Support · $topic", apiJson.encodeToString(SupportTicketRequest(requestId, repairId, topic, detail.trim())),
            System.currentTimeMillis(),
        ))
        scheduleSync()
    }

    override suspend fun pending(): List<PendingItem> = store.pending().map {
        PendingItem(it.requestId, it.kind, it.title, java.time.Instant.ofEpochMilli(it.createdAtMillis).atZone(IST).toLocalDateTime(), it.error)
    }

    override suspend fun dismiss(requestId: String) = store.removePending(requestId)

    override val changes: Flow<Int> get() = store.pendingChanges

    /**
     * Sends everything queued. Returns true when nothing is left to retry. A refusal (e.g. the slot filled up while
     * offline) is kept with its reason for the customer instead of being retried forever.
     */
    suspend fun flushPending(): Boolean {
        if (session.token == null) runCatching { open() }.onFailure { return false }
        var retry = false
        for (a in store.pending().filter { it.error == null }) {
            try {
                when (a.kind) {
                    KIND_BOOKING -> onBookingSent(a.requestId, save(net { api.createRepair(apiJson.decodeFromString(a.payload)) }).id)
                    KIND_TICKET -> net { api.createTicket(apiJson.decodeFromString(a.payload)) }
                }
                store.removePending(a.requestId)
            } catch (e: NotAllowedException) {
                store.failPending(a.requestId, e.message ?: "The server refused this.")
            } catch (e: OfflineException) {
                retry = true
            } catch (e: ServerException) {
                retry = true
            }
        }
        return !retry
    }

    companion object {
        const val KIND_BOOKING = "BOOKING"
        const val KIND_TICKET = "TICKET"
        private const val KEY_PROFILE = "profile"
        private const val KEY_DEVICES = "catalog.devices"
        private const val KEY_ISSUES = "catalog.issues"
        private const val KEY_ADDRESSES = "addresses"
    }
}
