package com.switchon.customer.ui

import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.BookingDraft
import com.switchon.customer.data.DeviceType
import com.switchon.customer.data.IssueType
import com.switchon.customer.ui.screens.CheckOrigin

/** A popular service on the Repairs hub: a device and issue pair that opens booking with both chosen. */
data class PopularService(val device: DeviceType, val issue: IssueType, val title: String)

val popularServices = listOf(
    PopularService(DeviceType.PHONE, IssueType.SCREEN, "Phone screen replacement"),
    PopularService(DeviceType.PHONE, IssueType.BATTERY, "Phone battery swap"),
    PopularService(DeviceType.LAPTOP, IssueType.PERFORMANCE, "Laptop speed-up"),
    PopularService(DeviceType.LAPTOP, IssueType.BATTERY, "Laptop battery replacement"),
)

/** Every way into booking, so each entry point prefills the draft the same way. */
sealed interface BookingEntry {
    /** "Start a repair": nothing chosen yet. */
    data object Blank : BookingEntry
    /** A device category on the Repairs hub. */
    data class Device(val device: DeviceType) : BookingEntry
    /** A popular service: device and issue chosen. */
    data class Service(val device: DeviceType, val issue: IssueType) : BookingEntry
}

/** Starts a fresh booking draft for [entry] (dropping any unfinished one) and returns the route to open. */
fun AppViewModel.begin(entry: BookingEntry): String {
    when (entry) {
        BookingEntry.Blank -> startBooking()
        is BookingEntry.Device -> startBooking(entry.device)
        is BookingEntry.Service -> startBooking(entry.device, entry.issue)
    }
    return Routes.BOOK
}

/** The two actions in the Home hero. */
enum class HeroAction { START_REPAIR, DEVICE_CHECK }

/** Route for a Home hero action. Starting a repair always begins a clean draft; the device check leaves it alone. */
fun AppViewModel.heroRoute(action: HeroAction): String = when (action) {
    HeroAction.START_REPAIR -> begin(BookingEntry.Blank)
    HeroAction.DEVICE_CHECK -> Routes.diagnostics(CheckOrigin.HOME)
}

/**
 * The step booking opens on. A popular service arrives with device and issue chosen, so it starts at details.
 * A device category starts at the issue step (where phones and tablets get the device-check offer). An issue
 * suggested by a device check also opens on the issue step, so the customer sees and confirms it.
 */
fun initialBookingStep(d: BookingDraft): Int = when {
    d.device != null && d.issue != null && !d.issueFromCheck -> 3
    d.device != null -> 2
    else -> 1
}

/** Whether the issue step should first offer the optional device check. */
fun showsCheckOffer(d: BookingDraft, step: Int) = step == 2 && !d.checkOffered && d.diagnosticReport == null
