package com.switchon.customer.ui

import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.Catalog
import com.switchon.customer.data.DeviceType
import com.switchon.customer.data.IssueType
import com.switchon.customer.data.NetworkMonitor
import com.switchon.customer.data.backend.MockBackend
import com.switchon.customer.data.diagnostics.DeviceInfo
import com.switchon.customer.data.diagnostics.DiagnosticRules
import com.switchon.customer.data.diagnostics.DiagnosticTest
import com.switchon.customer.data.remote.MemorySessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private class Online : NetworkMonitor { override val online = true }

/** Home hero and Repairs hub entry points: each opens the one booking flow with the right choices prefilled. */
class BookingEntryTest {
    private val vm = AppViewModel(MockBackend(), Online(), MemorySessionStore(), latency = false)
    private val report = DiagnosticRules.buildReport(
        DeviceInfo("Google", "Pixel 7", "Android 15", 35, DeviceType.PHONE),
        mapOf(DiagnosticTest.TOUCH to DiagnosticRules.touch(false, 60, 70)), emptyList(), LocalDateTime.of(2026, 10, 22, 9, 0),
    )

    // ---- Home hero ----

    @Test fun startARepairOpensACleanBookingOnTheDeviceStep() {
        vm.attachDiagnosticReport(report, fromBooking = false) // an unfinished draft from earlier
        assertEquals(Routes.BOOK, vm.heroRoute(HeroAction.START_REPAIR))
        assertNull(vm.draft.device)
        assertNull(vm.draft.diagnosticReport)
        assertEquals(1, initialBookingStep(vm.draft))
        assertEquals("contact prefilled from the profile", vm.profile.phoneDigits, vm.draft.contactPhone)
    }

    @Test fun runDeviceCheckOpensTheCheckFromHomeAndLeavesTheDraftAlone() {
        vm.startBooking(DeviceType.LAPTOP)
        val before = vm.draft
        assertEquals("diagnostics?from=HOME", vm.heroRoute(HeroAction.DEVICE_CHECK))
        assertEquals(before, vm.draft)
    }

    // ---- Repairs hub ----

    @Test fun aDeviceCategoryOpensTheIssueStepWithTheDeviceChosen() {
        DeviceType.entries.forEach { d ->
            assertEquals(Routes.BOOK, vm.begin(BookingEntry.Device(d)))
            assertEquals(d, vm.draft.device)
            assertNull(vm.draft.issue)
            assertEquals(2, initialBookingStep(vm.draft))
            // Phones and tablets get the device-check offer here; other devices get the "diagnosed at the shop" note.
            assertTrue(showsCheckOffer(vm.draft, 2))
        }
    }

    @Test fun aPopularServiceOpensDetailsWithDeviceAndIssueChosen() {
        popularServices.forEach { p ->
            vm.begin(BookingEntry.Service(p.device, p.issue))
            assertEquals(p.device, vm.draft.device)
            assertEquals(p.issue, vm.draft.issue)
            assertEquals(3, initialBookingStep(vm.draft))
            assertFalse(showsCheckOffer(vm.draft, 3))
            assertEquals(Catalog.estimate(p.device, p.issue, null), vm.draft.estimate)
        }
    }

    @Test fun everyHubTileHasAPublishedPrice() {
        popularServices.forEach { assertNotNull("${it.title} needs a base price", Catalog.basePrice(it.device, it.issue)) }
        DeviceType.entries.forEach { assertNotNull("${it.label} needs a from price", Catalog.fromPrice(it)) }
        assertEquals(399, Catalog.fromPrice(DeviceType.PHONE))
        assertEquals(6, DeviceType.entries.size) // Phone, Laptop, Tablet, PC, Wearable, Audio
    }

    @Test fun eachEntryStartsFreshSoNothingLeaksBetweenBookings() {
        vm.begin(BookingEntry.Device(DeviceType.PHONE))
        vm.attachDiagnosticReport(report, fromBooking = true)
        val first = vm.draft.requestId
        vm.begin(BookingEntry.Service(DeviceType.LAPTOP, IssueType.BATTERY))
        assertNull(vm.draft.diagnosticReport)
        assertFalse(vm.draft.checkOffered)
        assertTrue("a new request id, so it can't be mistaken for a retry", vm.draft.requestId != first)
    }

    @Test fun anIssueSuggestedByTheCheckOpensOnTheIssueStep() {
        vm.attachDiagnosticReport(report, fromBooking = false)
        assertEquals(IssueType.SCREEN, vm.draft.issue)
        assertEquals(2, initialBookingStep(vm.draft))
        assertFalse("already checked: no second offer", showsCheckOffer(vm.draft, 2))
    }

    @Test fun savedCheckRouteNamesTheRepair() {
        assertEquals("diagnostics?from=SAVED&repair=SO-482917", Routes.savedCheck("SO-482917"))
    }
}
