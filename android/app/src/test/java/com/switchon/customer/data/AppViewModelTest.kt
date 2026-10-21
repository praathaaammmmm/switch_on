package com.switchon.customer.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.switchon.customer.data.backend.MockBackend
import com.switchon.customer.data.remote.MemorySessionStore
import java.time.LocalDate

private class FakeNetwork(override var online: Boolean = true) : NetworkMonitor

class AppViewModelTest {

    private val net = FakeNetwork()
    private val vm = AppViewModel(MockBackend(), net, MemorySessionStore(), latency = false)

    private fun tomorrowSlot() = Slot(LocalDate.now().plusDays(1), 13)

    /** A complete, valid draft for a phone screen repair at the seeded home address. */
    private fun fillDraft() {
        vm.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        vm.draft = vm.draft.copy(brand = "Samsung", slot = tomorrowSlot())
    }

    private suspend fun bookOne(): String = vm.confirmBooking().getOrThrow()

    private fun Result<*>.error() = exceptionOrNull()

    // ---- Guest entry ------------------------------------------------------------------------------------

    @Test fun guestEntryOpensTheAppAndLogoutReturnsToOnboarding() {
        assertFalse(vm.signedIn)
        assertFalse(vm.introSeen)
        vm.continueAsGuest()
        assertTrue(vm.signedIn)

        vm.startBooking(DeviceType.PHONE)
        vm.logout()
        assertFalse(vm.signedIn)
        assertTrue("logout skips the splash and lands on onboarding", vm.introSeen)
        assertNull("in-progress booking is dropped", vm.draft.device)
        assertTrue("repairs are kept", vm.repairs.isNotEmpty())
    }

    // ---- Booking ----------------------------------------------------------------------------------------

    @Test fun startBookingPrefillsContactAndDefaultAddress() {
        vm.startBooking(DeviceType.LAPTOP, IssueType.BATTERY)
        assertEquals(vm.profile.name, vm.draft.contactName)
        assertEquals("9845012873", vm.draft.contactPhone)
        assertEquals(vm.defaultAddress?.id, vm.draft.addressId)
    }

    @Test fun bookingValidationReportsTheFirstMissingPiece() {
        vm.draft = BookingDraft()
        assertEquals("Choose the device.", vm.bookingProblem())
        vm.startBooking(DeviceType.AUDIO, IssueType.SCREEN) // not offered for audio
        assertEquals("Choose what's wrong with it.", vm.bookingProblem())
        vm.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        assertEquals("Choose a two-hour pickup window.", vm.bookingProblem())
        vm.draft = vm.draft.copy(slot = tomorrowSlot(), contactPhone = "12345")
        assertEquals("Enter all 10 digits (5/10).", vm.bookingProblem())
        vm.draft = vm.draft.copy(contactPhone = "5845012873")
        assertEquals("Indian mobile numbers start with 6, 7, 8 or 9.", vm.bookingProblem())
        vm.draft = vm.draft.copy(contactPhone = "9845012873", contactName = " ")
        assertNotNull(vm.bookingProblem())
        vm.draft = vm.draft.copy(contactName = "Priya", notes = "x".repeat(Validation.NOTES_MAX + 1))
        assertNotNull(vm.bookingProblem())
        vm.draft = vm.draft.copy(notes = "Dropped it")
        assertNull(vm.bookingProblem())
    }

    @Test fun unserviceableAddressBlocksBooking() {
        fillDraft()
        val far = Address("far", "Other", "1 Main Rd", "", "Fort Kochi", "Kochi", "682001")
        vm.addresses.add(far)
        vm.draft = vm.draft.copy(addressId = far.id)
        assertTrue(vm.pickupProblem()!!.contains("682001"))
    }

    @Test fun invalidDraftIsRejectedWithoutCreatingARepair() = runTest {
        fillDraft()
        vm.draft = vm.draft.copy(contactPhone = "98450")
        val before = vm.repairs.size
        val result = vm.confirmBooking()
        assertTrue(result.error() is NotAllowedException)
        assertEquals(before, vm.repairs.size)
    }

    @Test fun confirmBookingCreatesAScheduledRepairAndClearsTheDraft() = runTest {
        fillDraft()
        vm.draft = vm.draft.copy(photoUris = listOf("content://a", "content://b"), notes = "  Cracked corner  ")
        val id = bookOne()
        val r = vm.repair(id)!!
        assertTrue(Regex("SO-\\d{6}").matches(id))
        assertEquals(Stage.PICKUP_SCHEDULED, r.stage)
        assertEquals("9845012873", r.contactPhone)
        assertEquals("Cracked corner", r.notes)
        assertEquals(2, r.photoCount)
        assertEquals(Catalog.estimate(DeviceType.PHONE, IssueType.SCREEN, "Samsung")!!.first, r.estimateLow)
        assertTrue(r.audit.single().title == "Repair booked")
        assertNull(vm.draft.device)
    }

    @Test fun repeatedConfirmTapsBookOnlyOnce() = runTest {
        val slow = AppViewModel(MockBackend(), FakeNetwork(), MemorySessionStore(), latency = true)
        slow.startBooking(DeviceType.PHONE, IssueType.BATTERY)
        slow.draft = slow.draft.copy(slot = tomorrowSlot())
        val before = slow.repairs.size
        val first = async { slow.confirmBooking() }
        val second = async { slow.confirmBooking() }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is BusyException })
        assertEquals(before + 1, slow.repairs.size)
    }

    // ---- Quote ------------------------------------------------------------------------------------------

    @Test fun approvingTheQuoteStartsTheRepairOnce() = runTest {
        val id = "SO-482917"
        assertTrue(vm.approveQuote(id).isSuccess)
        val r = vm.repair(id)!!
        assertEquals(Stage.REPAIRING, r.stage)
        assertEquals(Decision.APPROVED, r.quote?.decision)
        assertTrue(vm.approveQuote(id).error() is NotAllowedException)
        assertTrue(vm.declineQuote(id, "Changed my mind").error() is NotAllowedException)
    }

    @Test fun repeatedApproveTapsApplyOnce() = runTest {
        val slow = AppViewModel(MockBackend(), FakeNetwork(), MemorySessionStore(), latency = true)
        val a = async { slow.approveQuote("SO-482917") }
        val b = async { slow.approveQuote("SO-482917") }
        assertEquals(1, listOf(a.await(), b.await()).count { it.isSuccess })
        assertEquals(1, slow.repair("SO-482917")!!.audit.count { it.title == "Quote approved" })
    }

    @Test fun decliningReturnsTheDeviceWithNothingToPay() = runTest {
        val id = "SO-482917"
        assertTrue(vm.declineQuote(id, "Too expensive").isSuccess)
        var r = vm.repair(id)!!
        assertEquals(Stage.RETURNING, r.stage)
        assertTrue(r.declined)
        assertEquals(0, r.amountDue)
        assertTrue(vm.advance(id).isSuccess)
        r = vm.repair(id)!!
        assertTrue(r.isDelivered)
        assertFalse(r.needsPayment)
        assertNull("no warranty on an unrepaired device", r.warrantyUntil)
        assertNotNull(vm.claimBlocker(r))
    }

    @Test fun trackingWaitsForTheCustomerAtTheQuote() = runTest {
        assertFalse(vm.advance("SO-482917").isSuccess)
        assertEquals(Stage.QUOTE_PENDING, vm.repair("SO-482917")!!.stage)
    }

    // ---- Payment ----------------------------------------------------------------------------------------

    private suspend fun deliveredAndDue(): String {
        val id = "SO-482917"
        vm.approveQuote(id).getOrThrow()
        repeat(3) { assertTrue(vm.advance(id).isSuccess) } // QC, returning, delivered
        assertTrue(vm.repair(id)!!.needsPayment)
        return id
    }

    @Test fun paymentIsNotPossibleBeforeDelivery() = runTest {
        assertTrue(vm.pay("SO-482917", PayMethod.CARD).error() is NotAllowedException)
    }

    @Test fun upiPaymentNeedsAValidUpiId() = runTest {
        val id = deliveredAndDue()
        assertTrue(vm.pay(id, PayMethod.UPI, upiId = "priya").error() is NotAllowedException)
        assertTrue(vm.repair(id)!!.needsPayment)
        assertTrue(vm.pay(id, PayMethod.UPI, upiId = "priya@okhdfcbank").isSuccess)
        val r = vm.repair(id)!!
        assertEquals(PayMethod.UPI, r.payment?.method)
        assertEquals(4700, r.payment?.amount)
        assertFalse(r.needsPayment)
        assertNotNull(r.invoiceNo)
        assertTrue("can't pay twice", vm.pay(id, PayMethod.CARD).error() is NotAllowedException)
    }

    @Test fun cashPaymentNeedsConfirmation() = runTest {
        val id = deliveredAndDue()
        assertTrue(vm.pay(id, PayMethod.CASH).error() is NotAllowedException)
        assertTrue(vm.pay(id, PayMethod.CASH, cashConfirmed = true).isSuccess)
        assertEquals(PayMethod.CASH, vm.repair(id)!!.payment?.method)
    }

    // ---- Warranty ---------------------------------------------------------------------------------------

    @Test fun warrantyEligibility() {
        assertNull("delivered 37 days ago", vm.claimBlocker(vm.repair("SO-471055")!!))
        assertTrue(vm.claimBlocker(vm.repair("SO-455320")!!)!!.startsWith("Cover ended"))
        assertTrue(vm.claimBlocker(vm.repair("SO-468204")!!)!!.contains("unrepaired"))
        assertNotNull("still in progress", vm.claimBlocker(vm.repair("SO-482917")!!))
        assertNotNull("cancelled", vm.claimBlocker(vm.repair("SO-463781")!!))
    }

    @Test fun raisingAClaimCreatesAFreeJobAndBlocksASecondOne() = runTest {
        val original = "SO-471055"
        val id = vm.raiseClaim(original, "Same fault is back", "", tomorrowSlot(), MockData.work.id).getOrThrow()
        val claim = vm.repair(id)!!
        assertEquals(original, claim.warrantyClaimFor)
        assertEquals(Stage.PICKUP_SCHEDULED, claim.stage)
        assertEquals(0, claim.amountDue)
        assertEquals("A claim for this repair is already open.", vm.claimBlocker(vm.repair(original)!!))
        assertTrue(vm.raiseClaim(original, "Again", "", tomorrowSlot(), MockData.work.id).error() is NotAllowedException)
    }

    @Test fun expiredWarrantyCannotBeClaimed() = runTest {
        val result = vm.raiseClaim("SO-455320", "Same fault is back", "", tomorrowSlot(), MockData.home.id)
        assertTrue(result.error() is NotAllowedException)
    }

    // ---- Cancel and reschedule --------------------------------------------------------------------------

    @Test fun cancelBeforePickupIsFree() = runTest {
        fillDraft()
        val id = bookOne()
        assertTrue(vm.cancel(id, "Booked by mistake").isSuccess)
        val r = vm.repair(id)!!
        assertTrue(r.isCancelled)
        assertFalse(r.isActive)
        assertTrue(vm.cancelled.any { it.id == id })
        assertTrue("can't cancel twice", vm.cancel(id, "Again").error() is NotAllowedException)
    }

    @Test fun cancelAndRescheduleStopOncePickedUp() = runTest {
        fillDraft()
        val id = bookOne()
        val later = Slot(LocalDate.now().plusDays(2), 9)
        assertTrue(vm.reschedule(id, later).isSuccess)
        assertEquals(later, vm.repair(id)!!.slot)
        vm.advance(id); vm.advance(id) // partner assigned, picked up
        assertTrue(vm.cancel(id, "Too late").error() is NotAllowedException)
        assertTrue(vm.reschedule(id, tomorrowSlot()).error() is NotAllowedException)
        assertFalse(vm.repair(id)!!.isCancelled)
    }

    // ---- Offline and server failure ---------------------------------------------------------------------

    @Test fun offlineActionsFailWithoutChangingAnything() = runTest {
        vm.simulateOffline = true
        assertTrue(vm.offline)
        assertTrue(vm.approveQuote("SO-482917").error() is OfflineException)
        assertEquals(Stage.QUOTE_PENDING, vm.repair("SO-482917")!!.stage)
        vm.simulateOffline = false
        net.online = false
        assertTrue(vm.offline)
        fillDraft()
        assertTrue(vm.confirmBooking().error() is OfflineException)
        net.online = true
        assertTrue(vm.confirmBooking().isSuccess)
    }

    @Test fun serverFailureHappensOnceThenRecovers() = runTest {
        vm.failNextRequest = true
        assertTrue(vm.approveQuote("SO-482917").error() is ServerException)
        assertFalse(vm.failNextRequest)
        assertEquals(Stage.QUOTE_PENDING, vm.repair("SO-482917")!!.stage)
        assertTrue(vm.approveQuote("SO-482917").isSuccess)
    }

    @Test fun resetControlsRestoreOrEmptyTheData() {
        vm.simulateOffline = true
        vm.resetDemo(empty = true)
        assertTrue(vm.repairs.isEmpty())
        assertFalse(vm.simulateOffline)
        vm.resetDemo(empty = false)
        assertEquals(MockData.repairs().size, vm.repairs.size)
    }
}
