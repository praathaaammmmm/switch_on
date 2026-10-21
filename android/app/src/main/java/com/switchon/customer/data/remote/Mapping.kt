package com.switchon.customer.data.remote

import com.switchon.customer.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

// Server JSON → the app's domain model. The server stores UTC; everything shown is in Asia/Kolkata.

fun utcToIst(iso: String): LocalDateTime = Instant.parse(iso).atZone(IST).toLocalDateTime()

/** The server's statuses folded onto the ten steps the tracker shows. */
fun stageFor(status: String): Stage = when (status) {
    "DRAFT", "PICKUP_REQUESTED", "PICKUP_SCHEDULED", "PICKUP_FAILED", "CANCELLED" -> Stage.PICKUP_SCHEDULED
    "PARTNER_ASSIGNED" -> Stage.PARTNER_ASSIGNED
    "PICKED_UP" -> Stage.PICKED_UP
    "AT_SHOP" -> Stage.AT_SHOP
    "DIAGNOSED" -> Stage.DIAGNOSED
    "QUOTE_PENDING" -> Stage.QUOTE_PENDING
    "REPAIRING" -> Stage.REPAIRING
    "QUALITY_CHECK" -> Stage.QUALITY_CHECK
    "QUOTE_DECLINED", "REPAIR_NOT_POSSIBLE", "RETURN_SCHEDULED" -> Stage.RETURNING
    "DELIVERED", "PAYMENT_PENDING", "PAYMENT_COMPLETED", "WARRANTY_ACTIVE" -> Stage.DELIVERED
    else -> Stage.PICKUP_SCHEDULED
}

fun AddressDto.toDomain() = Address(id, label, line1, line2, area, city, pincode, landmark, isDefault)

fun Address.toRequest() = AddressRequest(label, line1.trim(), line2.trim(), area.trim(), city.trim(), pincode, landmark.trim())

fun ProfileDto.toDomain() = Profile(name = name.orEmpty(), phone = phone?.let(::formatMobile).orEmpty(), email = email.orEmpty())

fun SupportTicketDto.toDomain() = Ticket(id, repairId, topic, detail, utcToIst(createdAt))

private val pickupCaptions = listOf("Front · at pickup", "Back · at pickup", "Sealed bag")

fun RepairDto.toDomain(): Repair {
    val photos = handovers.flatMap { h ->
        val at = utcToIst(h.at)
        when (h.stage) {
            "PICKUP" -> pickupCaptions.take(h.photoCount.coerceIn(1, 3)).map { HandoverPhoto(it, at, h.handledBy) }
            "SHOP_INTAKE" -> listOf(HandoverPhoto("Seal check · at shop", at, h.handledBy))
            "RETURN" -> listOf(HandoverPhoto("Resealed for return", at, h.handledBy))
            else -> emptyList()
        }
    }
    return Repair(
        id = id,
        device = runCatching { DeviceType.valueOf(deviceCategory) }.getOrDefault(DeviceType.PHONE),
        issue = runCatching { IssueType.valueOf(issue) }.getOrDefault(IssueType.OTHER),
        brand = brand, model = model, symptoms = symptoms, notes = notes, photoCount = photoCount,
        estimateLow = estimateLow, estimateHigh = estimateHigh, turnaround = turnaround,
        address = address.toDomain(),
        slot = Slot(LocalDate.parse(slot.date), slot.startHour),
        createdAt = utcToIst(createdAt),
        stage = stageFor(status),
        courier = partner?.let { Courier(it.name, it.phone, it.vehicle, it.rating, it.trips) },
        shop = shop?.let { Shop(it.name, it.area, it.rating, it.reviews, it.technician, it.verifiedSince) },
        sealCode = sealCode,
        quote = quote?.let { q ->
            Quote(
                diagnosis = q.diagnosis, findings = q.findings,
                items = q.items.map { LineItem(it.label, it.detail, it.amount, runCatching { ItemKind.valueOf(it.kind) }.getOrDefault(ItemKind.FEE)) },
                warranty = q.warranty, sentAt = utcToIst(q.sentAt),
                decision = q.decision?.let { runCatching { Decision.valueOf(it) }.getOrNull() },
                decidedAt = q.decidedAt?.let(::utcToIst), declineReason = q.declineReason,
            )
        },
        audit = events.map {
            AuditEvent(
                at = utcToIst(it.at), title = it.title, detail = it.message, actor = it.actorName,
                kind = runCatching { EventKind.valueOf(it.kind) }.getOrDefault(EventKind.NOTE), status = it.status,
            )
        },
        photos = photos,
        payment = payment?.let { Payment(runCatching { PayMethod.valueOf(it.method) }.getOrDefault(PayMethod.CARD), it.amount, utcToIst(it.at), it.reference) },
        deliveredAt = deliveredAt?.let(::utcToIst),
        cancellation = cancelledAt?.let { Cancellation(utcToIst(it), cancelReason ?: "Cancelled") },
        warrantyClaimFor = warrantyClaimFor,
        invoiceNo = invoiceNo,
        contactName = contactName,
        contactPhone = contactPhone,
        serverStatus = status,
        notPossible = events.any { it.status == "REPAIR_NOT_POSSIBLE" },
        pickupFailed = status == "PICKUP_FAILED",
        warrantyEndsOn = warranty?.endsOn?.let(LocalDate::parse),
    )
}
