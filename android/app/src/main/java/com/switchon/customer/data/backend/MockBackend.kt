package com.switchon.customer.data.backend

import com.switchon.customer.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * The demo build's backend: the sample repairs from [MockData], held in memory and changed by the same rules the
 * server enforces. Nothing survives an app restart.
 */
class MockBackend : RepairBackend {
    override val isDemo = true
    override val canAdvance = true

    private val repairs = MockData.repairs().toMutableList()
    private val addresses = mutableListOf(MockData.home, MockData.work)
    private val bookedRequests = mutableMapOf<String, String>()
    private val paymentKeys = mutableMapOf<String, String>()

    override suspend fun open() = MockData.profile
    override suspend fun cachedRepairs() = repairs.toList()
    override suspend fun cachedAddresses() = addresses.toList()
    override suspend fun repairs() = repairs.toList()
    override suspend fun repair(id: String) = find(id)
    override suspend fun addresses() = addresses.toList()

    override fun resetDemo(empty: Boolean) {
        repairs.clear()
        if (!empty) repairs.addAll(MockData.repairs())
        addresses.clear()
        addresses.addAll(listOf(MockData.home, MockData.work))
        bookedRequests.clear()
        paymentKeys.clear()
    }

    private fun find(id: String) = repairs.firstOrNull { it.id == id } ?: throw NotAllowedException("This repair no longer exists.")

    private fun put(r: Repair): Repair {
        val i = repairs.indexOfFirst { it.id == r.id }
        if (i >= 0) repairs[i] = r else repairs.add(0, r)
        return r
    }

    private fun newRepairId(): String {
        var id: String
        do id = "SO-" + Random.nextInt(100000, 999999) while (repairs.any { it.id == id })
        return id
    }

    private fun sealCode(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { a.random() }.joinToString("") + "-" + Random.nextInt(1000, 9999)
    }

    // ---- Addresses ----

    override suspend fun saveAddress(a: Address): Address {
        (Validation.required(a.line1, "Flat or house") ?: Validation.required(a.area, "The area") ?: Validation.required(a.city, "The city")
            ?: Validation.pincode(a.pincode))?.let { throw NotAllowedException(it) }
        val saved = a.copy(id = "addr-" + Random.nextInt(1000, 9999), isDefault = addresses.isEmpty())
        addresses.add(saved)
        return saved
    }

    override suspend fun deleteAddress(id: String) {
        val wasDefault = addresses.firstOrNull { it.id == id }?.isDefault == true
        addresses.removeAll { it.id == id }
        if (wasDefault && addresses.isNotEmpty()) addresses[0] = addresses[0].copy(isDefault = true)
    }

    override suspend fun makeDefaultAddress(id: String) {
        for (i in addresses.indices) addresses[i] = addresses[i].copy(isDefault = addresses[i].id == id)
    }

    /** Open slots for the next three days. Past windows today are hidden; a couple are shown as full. */
    override suspend fun slots(): Map<LocalDate, List<Pair<Slot, Boolean>>> = demoSlots()

    // ---- Booking ----

    override suspend fun book(draft: BookingDraft): Repair {
        bookedRequests[draft.requestId]?.let { return find(it) }
        val d = draft
        val (low, high) = d.estimate ?: throw NotAllowedException("Choose what's wrong with it.")
        val slot = d.slot ?: throw NotAllowedException("Choose a two-hour pickup window.")
        val address = addresses.firstOrNull { it.id == d.addressId } ?: throw NotAllowedException("Choose a pickup address.")
        val now = nowIst()
        val id = newRepairId()
        bookedRequests[d.requestId] = id
        return put(Repair(
            id = id, device = d.device!!, issue = d.issue!!, brand = d.brand, model = d.model, symptoms = d.symptoms.toList(),
            notes = d.notes.trim(), photoCount = d.photos, estimateLow = low, estimateHigh = high,
            turnaround = Catalog.turnaround(d.device, d.issue), address = address, slot = slot, createdAt = now,
            stage = Stage.PICKUP_SCHEDULED, contactName = d.contactName.trim(), contactPhone = d.contactPhone,
            audit = listOf(AuditEvent(now, "Repair booked", "Pickup ${slot.label}. Estimate ${rupeeRange(low, high)}.", "You", EventKind.STATUS)),
        ))
    }

    override suspend fun cancel(id: String, reason: String): Repair {
        val r = find(id)
        if (!r.canCancel) throw NotAllowedException("The partner has already set off, so this can't be cancelled here. Chat with us instead.")
        val now = nowIst()
        return put(r.copy(
            cancellation = Cancellation(now, reason),
            audit = r.audit + AuditEvent(now, "Cancelled before pickup", "$reason · no charge", "You", EventKind.STATUS),
        ))
    }

    override suspend fun reschedule(id: String, slot: Slot): Repair {
        val r = find(id)
        if (!r.canCancel) throw NotAllowedException("The partner has already set off, so the window can't change now.")
        return put(r.copy(slot = slot, audit = r.audit + AuditEvent(nowIst(), "Pickup rescheduled", slot.label, "You", EventKind.STATUS)))
    }

    // ---- Quote ----

    override suspend fun decideQuote(id: String, approve: Boolean, reason: String?): Repair {
        val r = find(id)
        val q = r.quote
        if (q == null || q.decision != null || r.stage != Stage.QUOTE_PENDING || !r.isActive) throw NotAllowedException("This quote has already been decided.")
        val now = nowIst()
        return if (approve) put(r.copy(
            stage = Stage.REPAIRING,
            quote = q.copy(decision = Decision.APPROVED, decidedAt = now),
            audit = r.audit + listOf(
                AuditEvent(now, "Quote approved", rupees(q.total), "You", EventKind.STATUS),
                AuditEvent(now, "Repair started", r.shop?.technician ?: "", r.shop?.name ?: "Repair shop", EventKind.STATUS),
            ),
        )) else {
            val seal = sealCode()
            val why = reason ?: "No reason given"
            put(r.copy(
                stage = Stage.RETURNING,
                quote = q.copy(decision = Decision.DECLINED, decidedAt = now, declineReason = why),
                sealCode = seal,
                audit = r.audit + listOf(
                    AuditEvent(now, "Quote declined", why, "You", EventKind.STATUS),
                    AuditEvent(now, "Resealed unrepaired", "Seal $seal · nothing changed on the device", r.shop?.name ?: "Repair shop", EventKind.CUSTODY),
                    AuditEvent(now, "Out for return", "Nothing to pay", r.courier?.name ?: "Switch On", EventKind.CUSTODY),
                ),
            ))
        }
    }

    // ---- Tracking (stand-in for the partner and shop apps) ----

    override suspend fun advance(id: String): Repair {
        val r = find(id)
        if (!r.isActive) throw NotAllowedException("This repair is closed.")
        if (r.stage == Stage.QUOTE_PENDING) throw NotAllowedException("Waiting on your decision")
        val next = r.stage.next ?: throw NotAllowedException("Nothing to advance.")
        val now = nowIst()
        val courier = r.courier ?: MockData.couriers.random()
        val shop = r.shop ?: MockData.shops.random()
        val updated = when (next) {
            Stage.PARTNER_ASSIGNED -> r.copy(
                courier = courier,
                audit = r.audit + AuditEvent(now, "Partner assigned", "${courier.name} · ${courier.vehicle}", "Switch On", EventKind.STATUS),
            )
            Stage.PICKED_UP -> {
                val seal = sealCode()
                r.copy(
                    sealCode = seal,
                    photos = listOf(
                        HandoverPhoto("Front · at pickup", now, courier.name),
                        HandoverPhoto("Back · at pickup", now, courier.name),
                        HandoverPhoto("Sealed bag", now, courier.name),
                    ),
                    audit = r.audit + listOf(
                        AuditEvent(now, "Identity checked at the door", "OTP matched", courier.name, EventKind.CUSTODY),
                        AuditEvent(now, "Device sealed and picked up", "Seal $seal · 3 handover photos", courier.name, EventKind.CUSTODY),
                    ),
                )
            }
            Stage.AT_SHOP -> r.copy(
                shop = shop,
                audit = r.audit + listOf(
                    AuditEvent(now, "Handed to repair shop", "Seal intact on arrival · ${shop.name}", shop.name, EventKind.CUSTODY),
                    AuditEvent(now, "Seal opened on camera", shop.technician, shop.name, EventKind.CUSTODY),
                ),
            )
            Stage.DIAGNOSED -> r.copy(
                audit = r.audit + AuditEvent(now, "Diagnosis complete", "Final quote being prepared", shop.name, EventKind.STATUS),
            )
            Stage.QUOTE_PENDING -> {
                val q = r.quote ?: if (r.warrantyClaimFor != null) warrantyQuote(r, now) else
                    Catalog.mockQuote(r.device, r.issue, r.estimateLow, r.estimateHigh, now)
                r.copy(
                    quote = q,
                    audit = r.audit + AuditEvent(now, "Quote sent for approval", "${rupees(q.total)} · ${estimateVerdict(r, q.total)}", shop.name, EventKind.ALERT),
                )
            }
            Stage.REPAIRING -> r
            Stage.QUALITY_CHECK -> r.copy(
                audit = r.audit + AuditEvent(now, "Repair finished", "Moved to quality check", shop.name, EventKind.STATUS),
            )
            Stage.RETURNING -> {
                val seal = sealCode()
                r.copy(
                    sealCode = seal,
                    audit = r.audit + listOf(
                        AuditEvent(now, "Quality check passed", "Full function test · 24 checks", shop.name, EventKind.STATUS),
                        AuditEvent(now, "Resealed for return", "Seal $seal", shop.name, EventKind.CUSTODY),
                        AuditEvent(now, "Out for delivery", courier.name, courier.name, EventKind.CUSTODY),
                    ),
                )
            }
            Stage.DELIVERED -> r.copy(
                deliveredAt = now,
                invoiceNo = if (r.amountDue > 0) "INV/2026-27/00" + Random.nextInt(5000, 9999) else null,
                audit = r.audit + AuditEvent(now, if (r.declined) "Returned to you" else "Delivered", "Seal checked and opened with you", courier.name, EventKind.CUSTODY),
            )
            Stage.PICKUP_SCHEDULED -> r
        }
        return put(updated.copy(stage = next))
    }

    private fun warrantyQuote(r: Repair, at: LocalDateTime) = Quote(
        diagnosis = "Same fault returned. Covered under warranty for ${r.warrantyClaimFor}.",
        findings = listOf("Original part replaced free", "No new physical damage found"),
        items = listOf(
            LineItem("Replacement part (warranty)", "Part", 0, ItemKind.PART),
            LineItem("Labour (warranty)", "Covered", 0, ItemKind.LABOUR),
            LineItem("Pickup and drop", "Doorstep, both ways", 0, ItemKind.FEE),
        ),
        warranty = "90 days from this repair", sentAt = at,
    )

    // ---- Payment ----

    override suspend fun pay(id: String, method: PayMethod, upiId: String, cashConfirmed: Boolean, idempotencyKey: String): Repair {
        val r = find(id)
        if (r.payment != null && paymentKeys[id] == idempotencyKey) return r
        if (!r.needsPayment) throw NotAllowedException(if (r.payment != null) "This repair is already paid." else "Nothing is due on this repair.")
        when (method) {
            PayMethod.UPI -> Validation.upiId(upiId)?.let { throw NotAllowedException(it) }
            PayMethod.CASH -> if (!cashConfirmed) throw NotAllowedException("Confirm you've handed over the cash.")
            PayMethod.CARD -> Unit
        }
        val now = nowIst()
        val ref = when (method) {
            PayMethod.UPI -> "UPI ${upiId.trim()} · ref " + Random.nextLong(400000000000, 499999999999)
            PayMethod.CARD -> "Card •••• 4417 · auth " + Random.nextInt(100000, 999999)
            PayMethod.CASH -> "Cash to ${r.courier?.name ?: "partner"}"
        }
        paymentKeys[id] = idempotencyKey
        return put(r.copy(
            payment = Payment(method, r.amountDue, now, ref),
            audit = r.audit + AuditEvent(now, "Paid by ${method.label}", "${rupees(r.amountDue)} · $ref", "You", EventKind.PAYMENT),
        ))
    }

    // ---- Warranty and support ----

    override suspend fun raiseClaim(originalId: String, problem: String, detail: String, slot: Slot, addressId: String, requestId: String): Repair {
        bookedRequests[requestId]?.let { return find(it) }
        val o = find(originalId)
        claimBlocker(o, repairs)?.let { throw NotAllowedException(it) }
        if (problem.isBlank()) throw NotAllowedException("Say what's wrong.")
        Validation.notes(detail)?.let { throw NotAllowedException(it) }
        val address = addresses.firstOrNull { it.id == addressId } ?: throw NotAllowedException("Choose a pickup address.")
        val now = nowIst()
        val id = newRepairId()
        bookedRequests[requestId] = id
        val claim = put(Repair(
            id = id, device = o.device, issue = o.issue, brand = o.brand, model = o.model, symptoms = listOf(problem),
            notes = detail.trim(), photoCount = 0, estimateLow = 0, estimateHigh = 0, turnaround = o.turnaround,
            address = address, slot = slot, createdAt = now, stage = Stage.PICKUP_SCHEDULED,
            warrantyClaimFor = o.id, contactName = o.contactName.ifBlank { MockData.profile.name },
            contactPhone = o.contactPhone.ifBlank { MockData.profile.phoneDigits },
            audit = listOf(AuditEvent(now, "Warranty claim raised", "For ${o.id} · $problem · free pickup", "You", EventKind.STATUS)),
        ))
        put(o.copy(audit = o.audit + AuditEvent(now, "Warranty claim raised", "New job $id", "You", EventKind.NOTE)))
        return claim
    }

    override suspend fun reportIssue(repairId: String?, topic: String, detail: String, requestId: String): Ticket =
        Ticket("TKT-" + Random.nextInt(20000, 99999), repairId, topic, detail.trim(), nowIst())
}

/** Why a repair can't take a warranty claim, or null when it can. Shared by the UI and the demo backend. */
fun claimBlocker(r: Repair, all: List<Repair>, today: LocalDate = todayIst()): String? = when {
    r.warrantyClaimFor != null -> "This is already a warranty job."
    r.declined -> "This device was returned unrepaired, so there's no repair to cover."
    !r.isDelivered -> "Cover starts on the day the repair is delivered."
    r.serverStatus != null && r.payment == null -> "Cover starts once the repair is paid."
    !r.warrantyActive(today) -> "Cover ended on ${r.warrantyUntil?.format(DMY)}."
    all.any { it.warrantyClaimFor == r.id && it.isActive } -> "A claim for this repair is already open."
    else -> null
}

/** Pickup windows for the demo: the next three days, past windows today hidden, two shown as full. */
fun demoSlots(today: LocalDate = todayIst(), nowHour: Int = nowIst().hour): Map<LocalDate, List<Pair<Slot, Boolean>>> =
    (0..2).associate { offset ->
        val day = today.plusDays(offset.toLong())
        day to listOf(9, 11, 13, 15, 17).filter { offset > 0 || it > nowHour + 1 }.map { h ->
            val full = (offset == 1 && h == 11) || (offset == 2 && h == 17)
            Slot(day, h) to !full
        }
    }.filterValues { it.isNotEmpty() }
