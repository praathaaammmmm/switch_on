package com.switchon.api

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServicesTest {
    private lateinit var tdb: TestDatabase
    private val clock = fixedClockAt(LocalDate.of(2026, 10, 21))
    private lateinit var s: Services

    @BeforeAll fun setUp() { tdb = TestDatabase(); s = Services(tdb.db, devConfig, clock) }
    @AfterAll fun tearDown() = tdb.close()

    private fun fx() = Fixture(s)
    private fun assertCode(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<ApiException> { block() }.code)

    // ---- Booking and pricing ----

    @Test fun `booking computes the estimate on the server and schedules the pickup`() {
        val f = fx()
        val r = f.book(brand = "Apple")
        assertTrue(Regex("^SO-\\d{6}$").matches(r.id))
        assertEquals("PICKUP_SCHEDULED", r.status)
        assertEquals(3899 to 5299, r.estimateLow to r.estimateHigh)
        assertEquals(listOf("PICKUP_REQUESTED", "PICKUP_SCHEDULED"), r.events.map { it.status })
        assertEquals("CUSTOMER", r.events.first().actorType)
        assertTrue(r.canCancel)
    }

    @Test fun `replaying a booking request returns the same repair`() {
        val f = fx()
        val key = UUID.randomUUID()
        val a = f.book(requestId = key)
        val b = f.book(requestId = key)
        assertEquals(a.id, b.id)
        assertEquals(1, s.repairs(f.customer).size)
    }

    @Test fun `booking rejects bad input`() {
        val f = fx()
        assertCode("not_offered") { f.book(category = "AUDIO", issue = "SCREEN", brand = null) }
        assertCode("invalid_request") { f.book(brand = "Nokia") }
        assertCode("slot_unavailable") { f.book(slot = SlotRef(s.todayIst().plusDays(10).toString(), 13)) }
        assertCode("slot_unavailable") { f.book(slot = f.tomorrow(hour = 12)) }
        assertCode("invalid_request") {
            s.createRepair(f.customer, CreateRepairRequest(UUID.randomUUID().toString(), "PHONE", "SCREEN", null, null, emptyList(), "", 0,
                f.address.id, f.tomorrow(), "Priya", "5845012873"))
        }
    }

    @Test fun `full slots can't be booked`() {
        val f = fx()
        val slot = SlotRef(s.todayIst().plusDays(2).toString(), 9)
        repeat(6) { f.book(slot = slot) }
        assertCode("slot_full") { f.book(slot = slot) }
        assertTrue(s.slots().slots.first { it.date == slot.date && it.startHour == 9 }.remaining == 0)
    }

    @Test fun `addresses outside service areas are refused`() {
        val f = fx()
        assertCode("not_serviceable") { s.createAddress(f.customer, AddressRequest("Other", "1 MG Rd", "", "Fort Kochi", "Kochi", "682001")) }
        assertEquals(true, s.serviceability("560038").serviceable)
        assertEquals(false, s.serviceability("682001").serviceable)
    }

    // ---- Quote decisions ----

    @Test fun `a quote can be decided once`() {
        val f = fx()
        val id = f.book().id
        f.advanceTo(id, RepairStatus.QUOTE_PENDING)
        val approved = s.decideQuote(f.customer, id, QuoteDecisionRequest("APPROVE"))
        assertEquals("REPAIRING", approved.status)
        assertEquals("APPROVED", approved.quote?.decision)
        assertCode("quote_already_decided") { s.decideQuote(f.customer, id, QuoteDecisionRequest("APPROVE")) }
        assertCode("quote_already_decided") { s.decideQuote(f.customer, id, QuoteDecisionRequest("DECLINE")) }
    }

    @Test fun `declining returns the device with nothing to pay and no warranty`() {
        val f = fx()
        val id = f.book().id
        f.advanceTo(id, RepairStatus.QUOTE_PENDING)
        val declined = s.decideQuote(f.customer, id, QuoteDecisionRequest("DECLINE", "Too expensive"))
        assertEquals("RETURN_SCHEDULED", declined.status)
        assertEquals("Too expensive", declined.quote?.declineReason)
        val delivered = s.advance(id)
        assertEquals("DELIVERED", delivered.status)
        assertEquals(0, delivered.amountDue)
        assertCode("payment_not_due") { f.pay(id) }
        assertNotNull(s.db.tx { s.claimBlocker(this, f.customer, id) })
    }

    @Test fun `quote can't be decided before it exists`() {
        val f = fx()
        val id = f.book().id
        assertCode("no_quote") { s.decideQuote(f.customer, id, QuoteDecisionRequest("APPROVE")) }
    }

    // ---- Payment ----

    @Test fun `payment is recorded once, with the server's amount`() {
        val f = fx()
        val id = f.book().id
        val due = f.advanceTo(id, RepairStatus.PAYMENT_PENDING)
        val total = due.quote!!.total
        assertEquals(total, due.amountDue)
        val key = UUID.randomUUID()
        val paid = f.pay(id, key)
        assertEquals("WARRANTY_ACTIVE", paid.status)
        assertEquals(total, paid.payment?.amount)
        assertNotNull(paid.invoiceNo)
        assertEquals(LocalDate.parse(paid.warranty!!.startsOn).plusDays(90).toString(), paid.warranty!!.endsOn)
        // Same key: a harmless replay. New key: refused.
        assertEquals(paid.payment, f.pay(id, key).payment)
        assertCode("payment_already_recorded") { f.pay(id, UUID.randomUUID(), method = "CARD") }
        assertEquals(1, s.db.tx { one("SELECT count(*) FROM payment_records WHERE repair_id = ?", id) { it.getInt(1) } })
        val inv = s.invoice(f.customer, id)
        assertEquals(total, inv.taxable + inv.cgst + inv.sgst)
    }

    @Test fun `concurrent payment taps record one payment`() {
        val f = fx()
        val id = f.book().id
        f.advanceTo(id, RepairStatus.PAYMENT_PENDING)
        val pool = Executors.newFixedThreadPool(4)
        val results = pool.invokeAll(List(4) { Callable { runCatching { f.pay(id) } } }).map { it.get() }
        pool.shutdown()
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as? ApiException)?.code == "payment_already_recorded" })
        assertEquals(1, s.db.tx { one("SELECT count(*) FROM payment_records WHERE repair_id = ?", id) { it.getInt(1) } })
    }

    @Test fun `upi needs a valid id and cash needs confirmation`() {
        val f = fx()
        val id = f.book().id
        f.advanceTo(id, RepairStatus.PAYMENT_PENDING)
        assertCode("invalid_request") { s.pay(f.customer, id, PaymentRequest("UPI", UUID.randomUUID().toString(), upiId = "priya")) }
        assertCode("invalid_request") { s.pay(f.customer, id, PaymentRequest("CASH", UUID.randomUUID().toString(), cashConfirmed = false)) }
        assertEquals("CASH", s.pay(f.customer, id, PaymentRequest("CASH", UUID.randomUUID().toString(), cashConfirmed = true)).payment?.method)
    }

    // ---- Cancel and reschedule ----

    @Test fun `cancel is allowed before pickup only`() {
        val f = fx()
        val a = f.book().id
        assertEquals("CANCELLED", s.cancel(f.customer, a, "Booked by mistake").status)
        assertCode("already_cancelled") { s.cancel(f.customer, a, "again") }

        val b = f.book().id
        s.advance(b) // partner assigned: still free to cancel
        val later = SlotRef(s.todayIst().plusDays(2).toString(), 15)
        assertEquals(later, s.reschedule(f.customer, b, later).slot)
        s.advance(b) // picked up
        assertCode("cancel_not_allowed") { s.cancel(f.customer, b, "too late") }
        assertCode("reschedule_not_allowed") { s.reschedule(f.customer, b, f.tomorrow()) }
    }

    @Test fun `a missed pickup can be rescheduled`() {
        val f = fx()
        val id = f.book().id
        assertEquals("PICKUP_FAILED", s.pickupFailed(id, "Nobody home").status)
        assertEquals("PICKUP_SCHEDULED", s.reschedule(f.customer, id, SlotRef(s.todayIst().plusDays(2).toString(), 11)).status)
    }

    @Test fun `repair not possible returns the device free`() {
        val f = fx()
        val id = f.book().id
        f.advanceTo(id, RepairStatus.DIAGNOSED)
        assertEquals("REPAIR_NOT_POSSIBLE", s.notPossible(id, "Board beyond repair").status)
        assertEquals("RETURN_SCHEDULED", s.advance(id).status)
        val r = s.advance(id)
        assertEquals("DELIVERED", r.status)
        assertEquals(0, r.amountDue)
    }

    @Test fun `invalid transitions are refused`() {
        val f = fx()
        val id = f.book().id
        s.advance(id); s.advance(id) // picked up
        assertCode("invalid_transition") { s.pickupFailed(id, "") }
    }

    // ---- Warranty ----

    @Test fun `warranty claims follow eligibility and expiry`() {
        val f = fx()
        val id = f.book().id
        f.advanceTo(id, RepairStatus.PAYMENT_PENDING)
        val claimFor = { s.createClaim(f.customer, WarrantyClaimRequest(UUID.randomUUID().toString(), id, "Same fault is back", "", f.address.id, f.tomorrow())) }
        assertCode("not_eligible") { claimFor() } // not paid yet, so no warranty
        f.pay(id)

        val (created, isNew) = claimFor()
        assertTrue(isNew)
        assertEquals("OPEN", created.claim.status)
        assertEquals(id, created.repair.warrantyClaimFor)
        assertEquals(0, created.repair.estimateHigh)
        assertCode("not_eligible") { claimFor() } // one open claim at a time

        // Claim job delivered: claim resolved, nothing to pay.
        val done = f.advanceTo(created.repair.id, RepairStatus.DELIVERED)
        assertEquals(0, done.amountDue)
        assertEquals("RESOLVED", s.claims(f.customer).first { it.claimRepairId == done.id }.status)

        // Expired cover.
        clock.plusDays(91)
        try {
            assertTrue(s.db.tx { s.claimBlocker(this, f.customer, id) }!!.startsWith("Cover ended"))
            assertCode("not_eligible") { claimFor() }
        } finally { clock.plusDays(-91) }
    }

    // ---- Isolation and history ----

    @Test fun `guests only ever see their own data`() {
        val alice = fx(); val bob = fx()
        val id = alice.book().id
        assertEquals(listOf(id), s.repairs(alice.customer).map { it.id })
        assertTrue(s.repairs(bob.customer).isEmpty())
        assertCode("not_found") { s.repair(bob.customer, id) }
        assertCode("not_found") { s.cancel(bob.customer, id, "not mine") }
        assertCode("not_found") { s.decideQuote(bob.customer, id, QuoteDecisionRequest("APPROVE")) }
        assertCode("not_found") { bob.pay(id) }
        assertCode("not_found") { s.invoice(bob.customer, id) }
        assertCode("address_missing") { bob.book(addressId = alice.address.id) }
        assertTrue(s.addresses(bob.customer).none { it.id == alice.address.id })
    }

    @Test fun `the same installation keeps its customer and history`() {
        val installation = UUID.randomUUID().toString()
        val first = s.guestSession(installation)
        val again = s.guestSession(installation)
        assertEquals(first.profile.customerId, again.profile.customerId)
        assertTrue(first.created && !again.created)
        assertEquals(UUID.fromString(first.profile.customerId), s.authenticate(again.token))
        assertCode("unauthorized") { s.authenticate("not-a-token") }
    }

    @Test fun `status events are append-only`() {
        val f = fx()
        val id = f.book().id
        val e = runCatching { s.db.tx { update("UPDATE repair_status_events SET title = 'x' WHERE repair_id = ?", id) } }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(generateSequence(e) { it.cause }.any { it.message?.contains("append-only") == true })
        assertNotNull(runCatching { s.db.tx { update("DELETE FROM repair_status_events WHERE repair_id = ?", id) } }.exceptionOrNull())
    }

    @Test fun `support tickets are idempotent and tied to the customer`() {
        val f = fx()
        val repair = f.book().id
        val key = UUID.randomUUID().toString()
        val (t1, new1) = s.createTicket(f.customer, SupportTicketRequest(key, repair, "Seal broken", "The seal was torn at the door"))
        val (t2, new2) = s.createTicket(f.customer, SupportTicketRequest(key, repair, "Seal broken", "The seal was torn at the door"))
        assertTrue(new1 && !new2)
        assertEquals(t1.id, t2.id)
        assertCode("invalid_request") { s.createTicket(f.customer, SupportTicketRequest(UUID.randomUUID().toString(), null, "Other", "short")) }
        assertCode("not_found") { s.createTicket(fx().customer, SupportTicketRequest(UUID.randomUUID().toString(), repair, "Mine?", "Not my repair at all")) }
        assertNull(s.tickets(fx().customer).firstOrNull())
    }

    @Test fun `demo seed recreates the app journeys`() {
        val f = fx()
        DemoSeeder(s).seed(f.customer)
        val statuses = s.repairs(f.customer).map { it.status }.sorted()
        assertEquals(listOf("CANCELLED", "DELIVERED", "QUOTE_PENDING", "WARRANTY_ACTIVE", "WARRANTY_ACTIVE"), statuses)
        val dell = s.repairs(f.customer).first { it.model == "Inspiron 15 3520" }
        assertNull(s.db.tx { s.claimBlocker(this, f.customer, dell.id) })
        val redmi = s.repairs(f.customer).first { it.model == "Redmi Note 12" }
        assertNotNull(s.db.tx { s.claimBlocker(this, f.customer, redmi.id) })
    }
}
