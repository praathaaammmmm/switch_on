package com.switchon.customer.data

import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DeviceType(val label: String) {
    PHONE("Phone"), LAPTOP("Laptop"), TABLET("Tablet"), PC("PC"), WEARABLE("Wearable"), AUDIO("Audio")
}

enum class IssueType(val label: String, val hint: String) {
    SCREEN("Screen", "Cracked, lines, dead touch"),
    BATTERY("Battery", "Drains fast, swollen, shuts off"),
    CHARGING("Charging", "Port, cable, won't charge"),
    WATER("Water damage", "Spills, drops in water"),
    PERFORMANCE("Performance", "Slow, hangs, overheating"),
    OTHER("Other", "Something else, we'll diagnose"),
}

/** The ten customer-facing steps, in order. */
enum class Stage(val label: String) {
    PICKUP_SCHEDULED("Pickup scheduled"),
    PARTNER_ASSIGNED("Partner assigned"),
    PICKED_UP("Picked up"),
    AT_SHOP("At repair shop"),
    DIAGNOSED("Diagnosis complete"),
    QUOTE_PENDING("Quote awaiting approval"),
    REPAIRING("Repair in progress"),
    QUALITY_CHECK("Quality check"),
    RETURNING("Returning to you"),
    DELIVERED("Delivered");

    val next: Stage? get() = entries.getOrNull(ordinal + 1)
}

data class Address(
    val id: String,
    val label: String,
    val line1: String,
    val line2: String,
    val area: String,
    val city: String,
    val pincode: String,
    val landmark: String = "",
    val isDefault: Boolean = false,
) {
    val short get() = "$area, $city $pincode"
    val full get() = listOf(line1, line2, area, "$city $pincode").filter { it.isNotBlank() }.joinToString(", ")
}

/** A two-hour pickup window starting at [startHour] (24h). */
data class Slot(val date: LocalDate, val startHour: Int) {
    val window get() = "${hour(startHour)} – ${hour(startHour + 2)}"
    val dayLabel get() = dayName(date)
    val label get() = "$dayLabel, ${date.format(DMY_SHORT)} · $window"
    /** For use mid-sentence: "tomorrow, 22 Oct" rather than "Tomorrow, 22 Oct". */
    val inlineLabel get() = if (dayLabel == "Today" || dayLabel == "Tomorrow") label.replaceFirstChar { it.lowercase() } else label
}

data class Courier(val name: String, val phone: String, val vehicle: String, val rating: Double, val trips: Int)

data class Shop(
    val name: String,
    val area: String,
    val rating: Double,
    val reviews: Int,
    val technician: String,
    val verifiedSince: String,
)

enum class ItemKind { PART, LABOUR, FEE }

data class LineItem(val label: String, val detail: String, val amount: Int, val kind: ItemKind)

enum class Decision { APPROVED, DECLINED }

data class Quote(
    val diagnosis: String,
    val findings: List<String>,
    val items: List<LineItem>,
    val warranty: String,
    val sentAt: LocalDateTime,
    val decision: Decision? = null,
    val decidedAt: LocalDateTime? = null,
    val declineReason: String? = null,
) {
    val total get() = items.sumOf { it.amount }
}

enum class EventKind { STATUS, CUSTODY, PAYMENT, NOTE, ALERT }

data class AuditEvent(
    val at: LocalDateTime,
    val title: String,
    val detail: String,
    val actor: String,
    val kind: EventKind,
    /** The server status this event moved the repair into, when it was a status change (null for notes and in demo data). */
    val status: String? = null,
)

data class HandoverPhoto(val caption: String, val at: LocalDateTime, val by: String)

enum class PayMethod(val label: String) { UPI("UPI"), CARD("Card"), CASH("Cash") }

data class Payment(val method: PayMethod, val amount: Int, val at: LocalDateTime, val ref: String)

data class Cancellation(val at: LocalDateTime, val reason: String)

data class Repair(
    val id: String,
    val device: DeviceType,
    val issue: IssueType,
    val brand: String?,
    val model: String?,
    val symptoms: List<String>,
    val notes: String,
    val photoCount: Int,
    val estimateLow: Int,
    val estimateHigh: Int,
    val turnaround: String,
    val address: Address,
    val slot: Slot,
    val createdAt: LocalDateTime,
    val stage: Stage,
    val courier: Courier? = null,
    val shop: Shop? = null,
    val sealCode: String? = null,
    val quote: Quote? = null,
    val audit: List<AuditEvent> = emptyList(),
    val photos: List<HandoverPhoto> = emptyList(),
    val payment: Payment? = null,
    val deliveredAt: LocalDateTime? = null,
    val cancellation: Cancellation? = null,
    val warrantyClaimFor: String? = null,
    val invoiceNo: String? = null,
    val contactName: String = "",
    /** Ten digits, no country code. */
    val contactPhone: String = "",
    /** The backend's own status name (e.g. PAYMENT_PENDING); null for demo data. */
    val serverStatus: String? = null,
    /** The shop couldn't repair it; it comes back free, like a declined quote. */
    val notPossible: Boolean = false,
    /** The partner couldn't collect it; the customer picks a new window or cancels. */
    val pickupFailed: Boolean = false,
    /** Cover end date as recorded by the server (starts once the repair is paid). */
    val warrantyEndsOn: LocalDate? = null,
) {
    val title get() = listOfNotNull(brand, model).joinToString(" ").ifBlank { device.label }
    val jobTitle get() = if (warrantyClaimFor != null) "Warranty claim · ${issue.label}" else "${issue.label} repair"
    val isCancelled get() = cancellation != null
    val isDelivered get() = stage == Stage.DELIVERED && !isCancelled
    val isActive get() = !isCancelled && !isDelivered
    /** Returned unrepaired: the customer declined the quote, or the shop couldn't fix it. */
    val declined get() = quote?.decision == Decision.DECLINED || notPossible
    /** What the customer owes on delivery. A declined repair or a warranty claim costs nothing. */
    val amountDue get() = if (declined || warrantyClaimFor != null || quote?.decision == null && serverStatus != null) 0 else quote?.total ?: 0
    val needsPayment get() = isDelivered && payment == null && amountDue > 0
    /** Free cancellation until the pickup partner sets off. */
    val canCancel get() = isActive && stage <= Stage.PARTNER_ASSIGNED
    val warrantyUntil: LocalDate? get() = when {
        serverStatus != null -> warrantyEndsOn
        isDelivered && !declined && deliveredAt != null -> deliveredAt.toLocalDate().plusDays(WARRANTY_DAYS)
        else -> null
    }

    fun warrantyActive(today: LocalDate = todayIst()) = warrantyUntil?.let { !today.isAfter(it) } ?: false
    fun warrantyDaysLeft(today: LocalDate = todayIst()) =
        warrantyUntil?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).coerceAtLeast(0) } ?: 0
}

const val WARRANTY_DAYS = 90L

/** Switch On operates in India: dates and times are always shown in Asia/Kolkata, whatever the phone's zone. */
val IST: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")
fun todayIst(): LocalDate = LocalDate.now(IST)
fun nowIst(): LocalDateTime = LocalDateTime.now(IST)

data class Profile(val name: String, val phone: String, val email: String) {
    /** The ten local digits of [phone], for prefilling the booking contact. */
    val phoneDigits get() = phone.filter(Char::isDigit).takeLast(10)
}

/** "98450 12873" from ten digits; anything else is returned as typed. */
fun formatMobile(digits: String) = if (digits.length == 10) "+91 ${digits.take(5)} ${digits.drop(5)}" else digits

data class NotificationPrefs(
    val statusUpdates: Boolean = true,
    val quoteAlerts: Boolean = true,
    val whatsapp: Boolean = true,
    val sms: Boolean = true,
    val offers: Boolean = false,
)

data class PrivacyPrefs(
    val shareDiagnostics: Boolean = true,
    val personalisedOffers: Boolean = false,
    val showPhoneToPartner: Boolean = false,
)

data class Ticket(val id: String, val repairId: String?, val topic: String, val detail: String, val at: LocalDateTime)

data class ChatMessage(val fromCustomer: Boolean, val text: String, val at: LocalDateTime)

// ---- Formatting -----------------------------------------------------------------------------------------

private val inr: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))

/** ₹ with Indian digit grouping: 1,24,999. */
fun rupees(amount: Int): String = "₹" + inr.format(amount)

fun rupeeRange(low: Int, high: Int) = "${rupees(low)} – ${rupees(high)}"

val DMY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

fun hour(h: Int): String {
    val h12 = ((h + 11) % 12) + 1
    return "$h12 ${if (h % 24 < 12) "AM" else "PM"}"
}

fun time(t: LocalDateTime): String = t.format(TIME)

fun dayName(d: LocalDate, today: LocalDate = todayIst()): String = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
}

/** "Today, 4:12 PM" or "14 Sep, 4:12 PM". */
fun stamp(t: LocalDateTime, today: LocalDate = todayIst()): String {
    val d = t.toLocalDate()
    val day = if (d == today || d == today.minusDays(1) || d == today.plusDays(1)) dayName(d, today) else d.format(DMY_SHORT)
    return "$day, ${time(t)}"
}
