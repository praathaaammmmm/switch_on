package com.switchon.api

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Creates a staff account directly (cheap hash iterations keep tests fast) and returns its principal. */
fun createAdmin(s: Services, role: AdminRole, email: String = "${role.name.lowercase()}-${UUID.randomUUID().toString().take(6)}@switchon.test", password: String = "correct horse battery"): AdminPrincipal {
    val id = UUID.randomUUID()
    s.db.tx { update("INSERT INTO admin_users (id, email, name, role, password_hash) VALUES (?,?,?,?,?)", id, email, role.name.lowercase().replaceFirstChar { it.uppercase() } + " Tester", role.name, Passwords.hash(password, 1_000)) }
    return AdminPrincipal(id, email, "Tester", role, "test-session")
}

/** Admin operations against a throwaway database: lifecycle, filtering, pricing history and the audit trail. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminServicesTest {
    private lateinit var tdb: TestDatabase
    private val clock = fixedClockAt(LocalDate.of(2026, 10, 21))
    private lateinit var s: Services
    private lateinit var f: Fixture
    private lateinit var repairs: AdminRepairs
    private lateinit var catalog: AdminCatalog
    private lateinit var ops: AdminOps
    private lateinit var owner: AdminPrincipal
    private lateinit var operations: AdminPrincipal

    @BeforeAll fun setUp() {
        tdb = TestDatabase()
        s = Services(tdb.db, devConfig, clock)
        s.db.tx { update("UPDATE pickup_slot_templates SET capacity = 500") } // many bookings share tomorrow's windows here
        f = Fixture(s)
        repairs = AdminRepairs(s); catalog = AdminCatalog(s); ops = AdminOps(s)
        owner = createAdmin(s, AdminRole.OWNER)
        operations = createAdmin(s, AdminRole.OPERATIONS)
    }

    @AfterAll fun tearDown() = tdb.close()

    private fun auditCount(entityId: String) = s.db.tx { one("SELECT count(*) FROM admin_audit_log WHERE entity_id = ?", entityId) { it.getInt(1) }!! }
    private fun shopFor(category: String) = catalog.partners().shops.first { category in it.capabilities && it.active }
    private fun courier() = catalog.partners().couriers.first { it.active }

    // ---- Repair filtering --------------------------------------------------------------------------------------

    @Test fun `filters by text, status, category, customer and paginates`() {
        val other = Fixture(s)
        val phone = f.book(category = "PHONE", issue = "SCREEN")
        val laptop = f.book(category = "LAPTOP", issue = "BATTERY", brand = "Dell")
        val theirs = other.book(category = "TABLET", issue = "SCREEN", brand = "Apple")

        assertEquals(listOf(phone.id), repairs.list(AdminRepairs.RepairFilter(q = phone.id)).items.map { it.id })
        assertTrue(repairs.list(AdminRepairs.RepairFilter(q = "dell")).items.any { it.id == laptop.id })
        assertTrue(repairs.list(AdminRepairs.RepairFilter(q = "98450")).items.map { it.id }.containsAll(listOf(phone.id, laptop.id)))
        val laptops = repairs.list(AdminRepairs.RepairFilter(category = "LAPTOP")).items
        assertTrue(laptops.all { it.deviceCategory == "LAPTOP" } && laptops.any { it.id == laptop.id })
        assertEquals(listOf(theirs.id), repairs.list(AdminRepairs.RepairFilter(customerId = other.customer)).items.map { it.id })
        assertTrue(repairs.list(AdminRepairs.RepairFilter(statuses = listOf("PICKUP_SCHEDULED"))).items.all { it.status == "PICKUP_SCHEDULED" })
        assertTrue(repairs.list(AdminRepairs.RepairFilter(statuses = listOf("DELIVERED"))).items.none { it.id == phone.id })
        // "%" and "_" are literal, not wildcards.
        assertEquals(0, repairs.list(AdminRepairs.RepairFilter(q = "%")).total)

        val page = repairs.list(AdminRepairs.RepairFilter(limit = 1, offset = 1))
        assertEquals(1, page.items.size)
        assertTrue(page.total >= 3)
    }

    @Test fun `delayed filter finds pickups whose window has passed`() {
        val r = f.book(slot = f.tomorrow(9))
        assertFalse(repairs.list(AdminRepairs.RepairFilter(delayedOnly = true)).items.any { it.id == r.id })
        val saved = clock.now
        try {
            clock.plusDays(2)
            val delayed = repairs.list(AdminRepairs.RepairFilter(delayedOnly = true)).items.first { it.id == r.id }
            assertEquals("Pickup window ended without a pickup", delayed.delayReason)
        } finally { clock.now = saved }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------------------------

    @Test fun `status changes follow the lifecycle and record customer-visible events`() {
        val r = f.book()
        // Not allowed by the lifecycle at all.
        assertEquals("invalid_transition", assertFailsWith<ApiException> { repairs.changeStatus(owner, r.id, StatusChangeRequest("AT_SHOP")) }.code)
        // Allowed by the lifecycle, but needs a partner first.
        assertEquals("not_allowed_here", assertFailsWith<ApiException> { repairs.changeStatus(owner, r.id, StatusChangeRequest("PARTNER_ASSIGNED")) }.code)

        // Assigning a partner moves a scheduled pickup to PARTNER_ASSIGNED, as the customer would see.
        repairs.assign(operations, r.id, AssignRequest(partnerId = courier().id))
        assertEquals("PARTNER_ASSIGNED", s.repair(f.customer, r.id).status)
        repairs.changeStatus(operations, r.id, StatusChangeRequest("PICKED_UP"))
        assertNotNull(s.repair(f.customer, r.id).sealCode)
        assertEquals("not_allowed_here", assertFailsWith<ApiException> { repairs.changeStatus(owner, r.id, StatusChangeRequest("AT_SHOP")) }.code)
        repairs.assign(operations, r.id, AssignRequest(shopId = shopFor("PHONE").id))
        repairs.changeStatus(operations, r.id, StatusChangeRequest("AT_SHOP"))
        // Cancelling after pickup isn't in the lifecycle.
        assertEquals("invalid_transition", assertFailsWith<ApiException> { repairs.cancel(owner, r.id, "Changed my mind") }.code)

        // Quote: draft, submit, and only the customer can approve.
        ops.saveDraft(operations, r.id, QuoteDraftRequest("Cracked display", listOf("Touch layer damaged"),
            listOf(LineItemDto("Display assembly", "Part", "PART", 3200), LineItemDto("Labour", "Bench", "LABOUR", 800))))
        val sent = ops.submitQuote(operations, r.id)
        assertEquals("QUOTE_PENDING", sent.repair.status)
        assertEquals(4000, sent.repair.quote!!.total)
        assertTrue(sent.nextStatuses.none { it.allowed }, "staff can't decide a quote")
        assertEquals("not_allowed_here", assertFailsWith<ApiException> { repairs.changeStatus(owner, r.id, StatusChangeRequest("REPAIRING")) }.code)
        s.decideQuote(f.customer, r.id, QuoteDecisionRequest("APPROVE"))

        repairs.changeStatus(operations, r.id, StatusChangeRequest("QUALITY_CHECK"))
        assertEquals("invalid_request", assertFailsWith<ApiException> { repairs.changeStatus(operations, r.id, StatusChangeRequest("REPAIRING")) }.code, "going back needs a note")
        repairs.changeStatus(operations, r.id, StatusChangeRequest("REPAIRING", "Hinge squeak after test"))
        repairs.changeStatus(operations, r.id, StatusChangeRequest("QUALITY_CHECK"))
        repairs.changeStatus(operations, r.id, StatusChangeRequest("RETURN_SCHEDULED"))
        val delivered = repairs.changeStatus(operations, r.id, StatusChangeRequest("DELIVERED"))
        assertEquals("PAYMENT_PENDING", delivered.repair.status, "an approved quote moves on to payment")
        assertEquals(4000, delivered.repair.amountDue)
        assertTrue(delivered.repair.events.any { it.actorName == STAFF_ACTOR || it.metadata["recordedBy"] == "admin" })
        assertEquals(7, delivered.audit.count { it.action == "repair.status" })
        assertEquals(2, delivered.audit.count { it.action == "repair.assign" })
    }

    @Test fun `viewers and support can't change repairs`() {
        val r = f.book()
        val viewer = createAdmin(s, AdminRole.VIEWER)
        val support = createAdmin(s, AdminRole.SUPPORT)
        assertEquals("forbidden", assertFailsWith<ApiException> { repairs.cancel(viewer, r.id, "No") }.code)
        assertEquals("forbidden", assertFailsWith<ApiException> { repairs.changeStatus(support, r.id, StatusChangeRequest("CANCELLED", "No")) }.code)
        repairs.addNote(support, r.id, "Customer called about timing")
        assertEquals("forbidden", assertFailsWith<ApiException> { repairs.addNote(viewer, r.id, "x") }.code)
        assertEquals("SUPPORT", repairs.detail(r.id).notes.single().let { it.author.substringBefore(' ').uppercase() })
    }

    @Test fun `assignment checks capability, capacity and the technician's shop`() {
        val r = f.book(category = "AUDIO", issue = "BATTERY", brand = "Sony")
        val partners = catalog.partners()
        val shop = partners.shops.first()
        catalog.upsertShop(owner, shop.id, ShopUpsertRequest(shop.name, shop.area, shop.capacity, listOf("PHONE"), true))
        assertEquals("shop_not_capable", assertFailsWith<ApiException> { repairs.assign(owner, r.id, AssignRequest(shopId = shop.id)) }.code)
        catalog.upsertShop(owner, shop.id, ShopUpsertRequest(shop.name, shop.area, shop.capacity, shop.capabilities, true))
        val elsewhere = partners.shops.first { it.id != shop.id }.technicians.first()
        repairs.assign(owner, r.id, AssignRequest(shopId = shop.id))
        assertEquals("technician_other_shop", assertFailsWith<ApiException> { repairs.assign(owner, r.id, AssignRequest(technicianId = elsewhere.id)) }.code)
        repairs.assign(owner, r.id, AssignRequest(technicianId = shop.technicians.first().id))
        assertEquals(shop.technicians.first().name, repairs.detail(r.id).summary.technicianName)

        val c = courier()
        catalog.upsertCourier(owner, c.id, CourierUpsertRequest(c.name, c.phone, c.vehicle, 0, true))
        assertEquals("partner_full", assertFailsWith<ApiException> { repairs.assign(owner, r.id, AssignRequest(partnerId = c.id)) }.code)
        catalog.upsertCourier(owner, c.id, CourierUpsertRequest(c.name, c.phone, c.vehicle, c.dailyCapacity, true))
    }

    @Test fun `staff reschedule checks blackouts and capacity`() {
        val r = f.book()
        val day = s.todayIst().plusDays(5)
        catalog.addBlackout(owner, BlackoutRequest(day.toString(), null, "Diwali"))
        assertEquals("slot_blocked", assertFailsWith<ApiException> { repairs.reschedule(owner, r.id, AdminRescheduleRequest(day.toString(), 11, "Customer asked")) }.code)
        val moved = repairs.reschedule(owner, r.id, AdminRescheduleRequest(day.plusDays(1).toString(), 11, "Customer asked"))
        assertEquals(day.plusDays(1).toString(), moved.repair.slot.date)
        assertTrue(moved.repair.events.last().title == "Pickup rescheduled")
        catalog.removeBlackout(owner, day.toString(), 11, "Reopened for late pickups")
        assertTrue(catalog.areas().blackouts.none { it.date == day.toString() && it.startHour == 11 })
    }

    // ---- Pricing ------------------------------------------------------------------------------------------------

    @Test fun `price changes need a reason and are recorded field by field`() {
        assertEquals("reason_required", assertFailsWith<ApiException> {
            catalog.updateService(owner, "PHONE", "CHARGING", ServiceUpdateRequest(basePrice = 799))
        }.code)
        assertEquals("forbidden", assertFailsWith<ApiException> {
            catalog.updateService(createAdmin(s, AdminRole.FINANCE), "PHONE", "CHARGING", ServiceUpdateRequest(basePrice = 799, reason = "Supplier cost up"))
        }.code)
        val updated = catalog.updateService(owner, "PHONE", "CHARGING", ServiceUpdateRequest(basePrice = 799, diagnosisFee = 149, turnaround = "1–2 days", reason = "Supplier cost up"))
        assertEquals(799, updated.entry.basePrice)
        assertEquals(149, updated.entry.diagnosisFee)
        val rows = updated.history.filter { it.reason == "Supplier cost up" }
        assertEquals(setOf("basePrice", "diagnosisFee", "turnaround"), rows.map { it.field }.toSet())
        assertEquals("699", rows.first { it.field == "basePrice" }.oldValue)
        assertEquals(owner.email, rows.first().changedBy)
        // New estimates use the new price immediately.
        assertEquals(799, s.estimate(EstimateRequest("PHONE", "CHARGING")).basePrice)
        assertEquals("no_change", assertFailsWith<ApiException> { catalog.updateService(owner, "PHONE", "CHARGING", ServiceUpdateRequest(basePrice = 799, reason = "Again please")) }.code)
    }

    @Test fun `price changes never rewrite booked estimates or approved quotes`() {
        val r = f.book(category = "PHONE", issue = "BATTERY", brand = "Samsung")
        val (low, high) = r.estimateLow to r.estimateHigh
        f.advanceTo(r.id, RepairStatus.QUOTE_PENDING)
        val approved = s.decideQuote(f.customer, r.id, QuoteDecisionRequest("APPROVE"))
        val quoteTotal = approved.quote!!.total
        val items = approved.quote!!.items

        catalog.updateService(owner, "PHONE", "BATTERY", ServiceUpdateRequest(basePrice = 1299, reason = "New battery supplier"))
        catalog.updateBrand(owner, catalog.catalog().brands.first { it.category == "PHONE" && it.name == "Samsung" }.id, BrandUpdateRequest(1.8, "Samsung parts cost more"))

        val after = s.repair(f.customer, r.id)
        assertEquals(low, after.estimateLow)
        assertEquals(high, after.estimateHigh)
        assertEquals(quoteTotal, after.quote!!.total)
        assertEquals(items, after.quote!!.items)
        assertEquals("APPROVED", after.quote!!.decision)
        // A new booking gets the new price.
        val fresh = f.book(category = "PHONE", issue = "BATTERY", brand = "Samsung")
        assertTrue(fresh.estimateLow > low)
        val history = catalog.history("PHONE", null, 50)
        assertTrue(history.any { it.scope == "BRAND" && it.brand == "Samsung" && it.oldValue == "1.5" && it.newValue == "1.8" })
    }

    @Test fun `inactive services disappear for customers but stay in the admin catalogue`() {
        catalog.updateService(owner, "AUDIO", "WATER", ServiceUpdateRequest(active = false, reason = "No liquid-damage bench for audio"))
        assertTrue(s.issues().issues.first { it.code == "WATER" }.offerings.none { it.deviceCategory == "AUDIO" })
        assertEquals("not_offered", assertFailsWith<ApiException> { s.estimate(EstimateRequest("AUDIO", "WATER")) }.code)
        assertFalse(catalog.catalog().entries.first { it.category == "AUDIO" && it.issue == "WATER" }.active)
        catalog.updateService(owner, "AUDIO", "WATER", ServiceUpdateRequest(active = true, reason = "Bench is back"))
        assertEquals(899, s.estimate(EstimateRequest("AUDIO", "WATER")).basePrice)
    }

    // ---- Audit --------------------------------------------------------------------------------------------------

    @Test fun `every mutation writes an audit row that can't be changed`() {
        val r = f.book()
        val before = auditCount(r.id)
        repairs.addNote(owner, r.id, "Called the customer")
        repairs.reschedule(owner, r.id, AdminRescheduleRequest(s.todayIst().plusDays(2).toString(), 15, "Customer travelling"))
        repairs.cancel(owner, r.id, "Customer cancelled by phone")
        assertEquals(before + 3, auditCount(r.id))
        val log = repairs.auditLog(AdminRepairs.AuditQuery(entityType = "repair", entityId = r.id))
        assertEquals(listOf("repair.status", "repair.reschedule", "repair.note"), log.items.map { it.action })
        assertTrue(log.items.all { it.adminEmail == owner.email })
        assertEquals("\"CANCELLED\"", log.items.first().after!!.toString().substringAfter("\"status\":").removeSuffix("}"))

        // A failed change leaves no audit row.
        assertFailsWith<ApiException> { repairs.cancel(owner, r.id, "Again") }
        assertEquals(before + 3, auditCount(r.id))

        // The database itself refuses edits and deletes.
        assertFailsWith<Exception> { s.db.tx { update("UPDATE admin_audit_log SET reason = 'edited' WHERE entity_id = ?", r.id) } }
        assertFailsWith<Exception> { s.db.tx { update("DELETE FROM admin_audit_log WHERE entity_id = ?", r.id) } }
        assertFailsWith<Exception> { s.db.tx { update("DELETE FROM service_price_history") } }
        assertEquals(before + 3, auditCount(r.id))
    }

    // ---- Payments, claims and support ---------------------------------------------------------------------------

    @Test fun `refunds are limited to what was paid and are audited`() {
        val r = f.book()
        f.advanceTo(r.id, RepairStatus.PAYMENT_PENDING)
        f.pay(r.id)
        val paid = s.repair(f.customer, r.id).payment!!.amount
        val finance = createAdmin(s, AdminRole.FINANCE)
        assertEquals("forbidden", assertFailsWith<ApiException> { ops.refund(operations, r.id, RefundRequest(100, "Goodwill gesture")) }.code)
        ops.refund(finance, r.id, RefundRequest(500, "Goodwill: late delivery"))
        assertEquals("refund_too_large", assertFailsWith<ApiException> { ops.refund(finance, r.id, RefundRequest(paid, "Full refund")) }.code)
        val inv = ops.reconcile(finance, r.id, ReconcileRequest("Matched UPI settlement"))
        assertEquals(500, inv.payment.refunded)
        assertNotNull(inv.payment.reconciledAt)
        assertEquals("already_reconciled", assertFailsWith<ApiException> { ops.reconcile(finance, r.id, ReconcileRequest()) }.code)
        assertEquals(setOf("payment.refund", "payment.reconcile"), repairs.auditLog(AdminRepairs.AuditQuery(entityId = r.id, entityType = "payment")).items.map { it.action }.toSet())
    }

    @Test fun `ticket replies reach the customer but internal notes don't`() {
        val (ticket, _) = s.createTicket(f.customer, SupportTicketRequest(UUID.randomUUID().toString(), null, "Billing", "Please resend my invoice"))
        val support = createAdmin(s, AdminRole.SUPPORT)
        ops.assignTicket(support, ticket.id, TicketAssignRequest(support.id.toString()))
        ops.addTicketMessage(support, ticket.id, "Sent it again to your email.", "CUSTOMER")
        ops.addTicketMessage(support, ticket.id, "Customer was charged twice? Check with finance.", "INTERNAL")
        val seen = s.tickets(f.customer).first { it.id == ticket.id }
        assertEquals(listOf("Sent it again to your email."), seen.replies.map { it.body })
        assertEquals(2, ops.ticket(ticket.id).messages.size)
        ops.setTicketStatus(support, ticket.id, TicketStatusRequest("CLOSED"))
        assertEquals("ticket_closed", assertFailsWith<ApiException> { ops.addTicketMessage(support, ticket.id, "One more thing", "CUSTOMER") }.code)
    }

    @Test fun `claims can be approved, questioned and rejected within the lifecycle`() {
        val r = f.book()
        f.advanceTo(r.id, RepairStatus.PAYMENT_PENDING)
        f.pay(r.id)
        val (created, _) = s.createClaim(f.customer, WarrantyClaimRequest(UUID.randomUUID().toString(), r.id, "Screen flickers", "", f.address.id, f.tomorrow()))
        val claimId = created.claim.id
        val support = createAdmin(s, AdminRole.SUPPORT)
        assertEquals("INFO_REQUESTED", ops.claimAction(support, claimId, "request-info", ClaimActionRequest("Does it flicker when charging?")).status)
        assertEquals("APPROVED", ops.claimAction(support, claimId, "approve", ClaimActionRequest()).status)
        // Still counts as open: the customer can't raise a second claim.
        assertNotNull(s.db.tx { s.claimBlocker(this, f.customer, r.id) })
        val rejected = ops.claimAction(support, claimId, "reject", ClaimActionRequest("Damage is physical, not covered"))
        assertEquals("REJECTED", rejected.status)
        assertEquals("CANCELLED", rejected.claimRepairStatus, "a claim job not yet picked up is cancelled")
        assertNull(s.db.tx { s.claimBlocker(this, f.customer, r.id) })
    }

    // ---- Team ---------------------------------------------------------------------------------------------------

    @Test fun `there is always an active owner`() {
        assertEquals("self_change", assertFailsWith<ApiException> { ops.updateUser(owner, owner.id.toString(), AdminUpdateRequest(role = "VIEWER")) }.code)
        assertEquals("forbidden", assertFailsWith<ApiException> { ops.updateUser(operations, owner.id.toString(), AdminUpdateRequest(role = "VIEWER")) }.code)
        // Leave exactly one active owner in the database: `sole`.
        val sole = createAdmin(s, AdminRole.OWNER)
        val others = s.db.tx { query("SELECT id FROM admin_users WHERE role = 'OWNER' AND active AND id <> ?", sole.id) { it.uuid(1)!! } }
        s.db.tx { update("UPDATE admin_users SET active = false WHERE id = ANY(?::uuid[])", others.map { it.toString() }) }
        try {
            // Another owner session (its account is inactive in the database, so it isn't counted) can't remove the last one.
            assertEquals("last_owner", assertFailsWith<ApiException> { ops.updateUser(owner, sole.id.toString(), AdminUpdateRequest(role = "SUPPORT")) }.code)
            assertEquals("last_owner", assertFailsWith<ApiException> { ops.updateUser(owner, sole.id.toString(), AdminUpdateRequest(active = false)) }.code)
        } finally {
            s.db.tx { update("UPDATE admin_users SET active = true WHERE id = ANY(?::uuid[])", others.map { it.toString() }) }
        }
        assertEquals("weak_password", assertFailsWith<ApiException> { ops.createUser(owner, AdminCreateRequest("new@switchon.test", "New Person", "SUPPORT", "short")) }.code)
        val created = ops.createUser(owner, AdminCreateRequest("new@switchon.test", "New Person", "SUPPORT", "a long enough password"))
        assertEquals("email_taken", assertFailsWith<ApiException> { ops.createUser(owner, AdminCreateRequest("NEW@switchon.test", "Dup", "SUPPORT", "a long enough password")) }.code)
        assertEquals("SUPPORT", created.role)
    }
}

/** Pure rules: when a repair counts as delayed. */
class DelaysTest {
    private val now = Instant.parse("2026-10-21T10:00:00Z") // 15:30 IST

    @Test fun `pickups are delayed only after their window ends`() {
        val today = LocalDate.of(2026, 10, 21)
        assertNull(Delays.reason(RepairStatus.PICKUP_SCHEDULED, now, today, 15, now)) // 3–5 PM window still open
        assertEquals("Pickup window ended without a pickup", Delays.reason(RepairStatus.PARTNER_ASSIGNED, now, today, 11, now))
    }

    @Test fun `in-shop stages have limits, finished and cancelled repairs never delay`() {
        val day = LocalDate.of(2026, 10, 20)
        assertNull(Delays.reason(RepairStatus.QUOTE_PENDING, now.minusSeconds(47 * 3600), day, 9, now))
        assertEquals("Quote sent for approval for 49 h (limit 48 h)", Delays.reason(RepairStatus.QUOTE_PENDING, now.minusSeconds(49 * 3600), day, 9, now))
        assertNotNull(Delays.reason(RepairStatus.AT_SHOP, now.minusSeconds(25 * 3600), day, 9, now))
        listOf(RepairStatus.CANCELLED, RepairStatus.WARRANTY_ACTIVE, RepairStatus.DELIVERED).forEach {
            assertNull(Delays.reason(it, now.minusSeconds(900 * 3600), day, 9, now))
        }
    }
}
