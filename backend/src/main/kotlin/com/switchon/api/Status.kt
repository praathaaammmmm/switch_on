package com.switchon.api

import com.switchon.api.RepairStatus.*

/**
 * The repair lifecycle. Happy path:
 * DRAFT → PICKUP_REQUESTED → PICKUP_SCHEDULED → PARTNER_ASSIGNED → PICKED_UP → AT_SHOP → DIAGNOSED →
 * QUOTE_PENDING → REPAIRING → QUALITY_CHECK → RETURN_SCHEDULED → DELIVERED → PAYMENT_PENDING →
 * PAYMENT_COMPLETED → WARRANTY_ACTIVE.
 * Side paths: CANCELLED (before pickup), PICKUP_FAILED, QUOTE_DECLINED and REPAIR_NOT_POSSIBLE (both return
 * the device unrepaired). Warranty claim jobs and unrepaired returns end at DELIVERED.
 */
enum class RepairStatus(val title: String, val message: String) {
    DRAFT("Draft saved", "Your booking is saved but not yet sent."),
    PICKUP_REQUESTED("Pickup requested", "We've received your booking."),
    PICKUP_SCHEDULED("Pickup scheduled", "Your pickup window is confirmed."),
    PARTNER_ASSIGNED("Partner assigned", "A verified pickup partner is on your job."),
    PICKED_UP("Picked up", "Your device was sealed and picked up."),
    AT_SHOP("At repair shop", "Your device reached the repair shop with the seal intact."),
    DIAGNOSED("Diagnosis complete", "The shop has diagnosed the fault. Your quote is being prepared."),
    QUOTE_PENDING("Quote sent for approval", "Your itemised quote is ready. Nothing starts until you approve it."),
    REPAIRING("Repair in progress", "The repair has started."),
    QUALITY_CHECK("Quality check", "The repair is done and in quality check."),
    RETURN_SCHEDULED("Return scheduled", "Your device is resealed and on its way back."),
    DELIVERED("Delivered", "Your device has been delivered."),
    PAYMENT_PENDING("Payment pending", "Check your device, then pay by UPI, card or cash."),
    PAYMENT_COMPLETED("Payment completed", "Thanks, payment received. Your invoice is ready."),
    WARRANTY_ACTIVE("90-day warranty active", "Your repair is covered for 90 days."),
    CANCELLED("Cancelled before pickup", "Your booking was cancelled. Nothing was charged."),
    PICKUP_FAILED("Pickup missed", "We couldn't complete the pickup. Choose a new window or cancel for free."),
    QUOTE_DECLINED("Quote declined", "You declined the quote. We'll return your device as it was, free."),
    REPAIR_NOT_POSSIBLE("Can't be repaired", "The shop couldn't repair this device. It's coming back to you free.");

    val beforePickup get() = this in setOf(DRAFT, PICKUP_REQUESTED, PICKUP_SCHEDULED, PARTNER_ASSIGNED, PICKUP_FAILED)
    val isTerminal get() = allowedNext.isEmpty()
    val allowedNext: Set<RepairStatus> get() = TRANSITIONS[this].orEmpty()
}

val TRANSITIONS: Map<RepairStatus, Set<RepairStatus>> = mapOf(
    DRAFT to setOf(PICKUP_REQUESTED, CANCELLED),
    PICKUP_REQUESTED to setOf(PICKUP_SCHEDULED, CANCELLED),
    PICKUP_SCHEDULED to setOf(PARTNER_ASSIGNED, PICKUP_FAILED, CANCELLED),
    PARTNER_ASSIGNED to setOf(PICKED_UP, PICKUP_FAILED, CANCELLED),
    PICKUP_FAILED to setOf(PICKUP_SCHEDULED, CANCELLED),
    PICKED_UP to setOf(AT_SHOP),
    AT_SHOP to setOf(DIAGNOSED, REPAIR_NOT_POSSIBLE),
    DIAGNOSED to setOf(QUOTE_PENDING, REPAIR_NOT_POSSIBLE),
    QUOTE_PENDING to setOf(REPAIRING, QUOTE_DECLINED),
    QUOTE_DECLINED to setOf(RETURN_SCHEDULED),
    REPAIR_NOT_POSSIBLE to setOf(RETURN_SCHEDULED),
    REPAIRING to setOf(QUALITY_CHECK, REPAIR_NOT_POSSIBLE),
    QUALITY_CHECK to setOf(RETURN_SCHEDULED, REPAIRING),
    RETURN_SCHEDULED to setOf(DELIVERED),
    DELIVERED to setOf(PAYMENT_PENDING),
    PAYMENT_PENDING to setOf(PAYMENT_COMPLETED),
    PAYMENT_COMPLETED to setOf(WARRANTY_ACTIVE),
)

fun canTransition(from: RepairStatus, to: RepairStatus) = to in from.allowedNext
