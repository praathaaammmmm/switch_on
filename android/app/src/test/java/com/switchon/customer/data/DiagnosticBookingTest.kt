package com.switchon.customer.data

import com.switchon.customer.data.backend.MockBackend
import com.switchon.customer.data.diagnostics.*
import com.switchon.customer.data.remote.MemorySessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

private class OnlineNet : NetworkMonitor { override val online = true }

class DiagnosticBookingTest {
    private val vm = AppViewModel(MockBackend(), OnlineNet(), MemorySessionStore(), latency = false)
    private val tablet = DeviceInfo("Samsung", "Galaxy Tab A8", "Android 14", 34, DeviceType.TABLET)

    private fun report(vararg rs: DiagnosticResult) =
        DiagnosticRules.buildReport(tablet, rs.associateBy { it.test }, emptyList(), LocalDateTime.of(2026, 10, 22, 9, 0))

    private val screenTrouble get() = report(DiagnosticRules.confirmed(DiagnosticTest.TOUCH, false, "Some areas did not respond"))

    @Test fun newDraftsHaveNoReport() {
        vm.startBooking(DeviceType.PHONE)
        assertNull(vm.draft.diagnosticReport)
        assertFalse(vm.draft.checkOffered)
    }

    @Test fun fromHomeTheCheckStartsABookingForTheCheckedDevice() {
        val r = screenTrouble
        vm.attachDiagnosticReport(r, fromBooking = false)
        assertSame(r, vm.draft.diagnosticReport)
        assertEquals(DeviceType.TABLET, vm.draft.device)
        assertEquals(IssueType.SCREEN, vm.draft.issue)
        assertTrue(vm.draft.issueFromCheck)
        assertTrue(vm.draft.checkOffered)
        assertEquals("contact still prefilled", vm.profile.phoneDigits, vm.draft.contactPhone)
    }

    @Test fun inBookingTheCustomersChoicesWin() {
        vm.startBooking(DeviceType.PHONE, IssueType.BATTERY)
        vm.attachDiagnosticReport(screenTrouble, fromBooking = true)
        assertEquals(DeviceType.PHONE, vm.draft.device)
        assertEquals("the suggestion never overrides a chosen issue", IssueType.BATTERY, vm.draft.issue)
        assertFalse(vm.draft.issueFromCheck)
    }

    @Test fun aCleanReportSuggestsNothing() {
        vm.startBooking(DeviceType.PHONE)
        vm.attachDiagnosticReport(report(DiagnosticRules.confirmed(DiagnosticTest.TOUCH, true, "OK")), fromBooking = true)
        assertNull(vm.draft.issue)
        assertFalse(vm.draft.issueFromCheck)
    }

    @Test fun decliningTheCheckDoesNotBlockBooking() = runTest {
        vm.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        vm.declineDiagnosticCheck()
        assertTrue(vm.draft.checkOffered)
        assertNull(vm.draft.diagnosticReport)
        vm.draft = vm.draft.copy(slot = Slot(LocalDate.now().plusDays(1), 13))
        assertTrue(vm.confirmBooking().isSuccess)
    }

    @Test fun aReportDoesNotChangeTheEstimateAndTheBookingGoesThrough() = runTest {
        vm.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        val before = vm.draft.estimate
        vm.attachDiagnosticReport(screenTrouble, fromBooking = true)
        assertEquals(before, vm.draft.estimate)
        vm.draft = vm.draft.copy(slot = Slot(LocalDate.now().plusDays(1), 13))
        assertTrue(vm.confirmBooking().isSuccess)
        assertNull("draft cleared after booking", vm.draft.diagnosticReport)
    }

    @Test fun bookingWithACheckKeepsTheReportAndHandOffSummaryWithTheRepair() = runTest {
        vm.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        val r = screenTrouble
        vm.attachDiagnosticReport(r, fromBooking = true)
        vm.draft = vm.draft.copy(slot = Slot(LocalDate.now().plusDays(1), 13))
        val id = vm.confirmBooking().getOrThrow()
        val saved = vm.savedReport(id)!!
        assertEquals(r, saved)
        assertTrue(saved.summary.startsWith("Preliminary check"))
        assertTrue(saved.summary.contains("touch response issues"))
        assertTrue(saved.summary.endsWith("before confirming the fault or price."))
    }

    @Test fun bookingWithoutACheckSavesNothing() = runTest {
        vm.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        vm.draft = vm.draft.copy(slot = Slot(LocalDate.now().plusDays(1), 13))
        val id = vm.confirmBooking().getOrThrow()
        assertNull(vm.savedReport(id))
    }

    @Test fun savedReportsLinkOnlyKnownBookings() {
        val store = MemorySavedReports()
        store.link("unknown-request", "SO-000001")
        assertNull(store.forRepair("SO-000001"))
        store.save("req-1", screenTrouble)
        assertNull("not linked until the server accepts the booking", store.forRepair("SO-000002"))
        store.link("req-1", "SO-000002")
        assertEquals(screenTrouble, store.forRepair("SO-000002"))
    }

    @Test fun changingTheDeviceDropsTheReportAndOffersAgain() {
        vm.attachDiagnosticReport(screenTrouble, fromBooking = false)
        vm.chooseDevice(DeviceType.TABLET) // same device: nothing changes
        assertTrue(vm.draft.diagnosticReport != null)
        vm.chooseDevice(DeviceType.LAPTOP)
        assertNull(vm.draft.diagnosticReport)
        assertNull(vm.draft.issue)
        assertFalse(vm.draft.checkOffered)
        assertFalse(vm.draft.issueFromCheck)
    }
}
