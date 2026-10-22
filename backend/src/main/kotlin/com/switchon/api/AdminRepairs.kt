package com.switchon.api

import com.switchon.api.RepairStatus.*
import kotlinx.serialization.json.JsonElement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * When an active repair counts as delayed. Before pickup: the pickup window has ended. After that: the repair has
 * sat in one status longer than that stage should take. Pure, so it is unit-tested directly.
 */
object Delays {
    private val limits: Map<RepairStatus, Duration> = mapOf(
        PICKUP_FAILED to Duration.ofHours(24),
        PICKED_UP to Duration.ofHours(12),
        AT_SHOP to Duration.ofHours(24),
        DIAGNOSED to Duration.ofHours(12),
        QUOTE_PENDING to Duration.ofHours(48),
        REPAIRING to Duration.ofHours(72),
        QUALITY_CHECK to Duration.ofHours(24),
        RETURN_SCHEDULED to Duration.ofHours(24),
        PAYMENT_PENDING to Duration.ofHours(72),
    )

    fun reason(status: RepairStatus, updatedAt: Instant, slotDate: LocalDate, slotStartHour: Int, now: Instant): String? {
        if (status in setOf(PICKUP_REQUESTED, PICKUP_SCHEDULED, PARTNER_ASSIGNED)) {
            val windowEnd = slotDate.atTime(slotStartHour + 2, 0).atZone(IST).toInstant()
            return if (now.isAfter(windowEnd)) "Pickup window ended without a pickup" else null
        }
        val limit = limits[status] ?: return null
        val waited = Duration.between(updatedAt, now)
        return if (waited > limit) "${status.title} for ${waited.toHours()} h (limit ${limit.toHours()} h)" else null
    }
}

/** Statuses a repair is "active" in: booked and not yet finished, cancelled or closed out. */
val ACTIVE_STATUSES = RepairStatus.entries.filter { it !in setOf(DRAFT, CANCELLED, DELIVERED, PAYMENT_COMPLETED, WARRANTY_ACTIVE) }.map { it.name }

const val STAFF_ACTOR = "Switch On team"

class AdminRepairs(private val s: Services) {
    private val db = s.db

    // ==========================================================================================================
    // Summaries
    // ==========================================================================================================

    internal val summarySelect = """
        SELECT r.*, c.display_name AS customer_name, p.name AS partner_name, sh.name AS shop_name, t.name AS technician_name,
               r.address_snapshot->>'city' AS city, r.address_snapshot->>'pincode' AS pincode
        FROM repairs r JOIN customers c ON c.id = r.customer_id
        LEFT JOIN pickup_partners p ON p.id = r.partner_id
        LEFT JOIN repair_shops sh ON sh.id = r.shop_id
        LEFT JOIN technicians t ON t.id = r.technician_id
    """.trimIndent()

    internal fun summary(rs: ResultSet): RepairSummary {
        val status = RepairStatus.valueOf(rs.getString("status"))
        val updated = rs.instant("updated_at")!!
        val delay = Delays.reason(status, updated, rs.date("slot_date")!!, rs.getInt("slot_start_hour"), s.now())
        return RepairSummary(
            id = rs.getString("id"), status = status.name, statusTitle = status.title, deviceCategory = rs.getString("device_category"),
            issue = rs.getString("issue_code"), brand = rs.getString("brand"), model = rs.getString("model"),
            customerId = rs.uuid("customer_id").toString(), customerName = rs.getString("customer_name"),
            contactName = rs.getString("contact_name"), contactPhone = rs.getString("contact_phone"),
            city = rs.getString("city") ?: "", pincode = rs.getString("pincode") ?: "",
            slotDate = rs.date("slot_date").toString(), slotStartHour = rs.getInt("slot_start_hour"),
            estimateLow = rs.getInt("estimate_low_inr"), estimateHigh = rs.getInt("estimate_high_inr"),
            createdAt = rs.instant("created_at").toString(), updatedAt = updated.toString(),
            partnerName = rs.getString("partner_name"), shopName = rs.getString("shop_name"), technicianName = rs.getString("technician_name"),
            delayed = delay != null, delayReason = delay, warrantyClaimFor = rs.getString("warranty_claim_for"),
        )
    }

    data class RepairFilter(
        val q: String? = null,
        val statuses: List<String> = emptyList(),
        val category: String? = null,
        val delayedOnly: Boolean = false,
        val activeOnly: Boolean = false,
        val from: LocalDate? = null,
        val to: LocalDate? = null,
        val partnerId: UUID? = null,
        val shopId: UUID? = null,
        val customerId: UUID? = null,
        val limit: Int = 50,
        val offset: Int = 0,
    )

    /**
     * Repairs matching every given filter, newest first. Text search covers the repair id, contact name and phone,
     * customer name, brand and model. "Delayed" is computed from the lifecycle rules, so it filters after the query.
     */
    fun list(f: RepairFilter): RepairListResponse = db.tx {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        f.q?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            where += "(r.id ILIKE ? OR r.contact_name ILIKE ? OR r.contact_phone LIKE ? OR coalesce(c.display_name, '') ILIKE ? OR coalesce(r.brand, '') ILIKE ? OR coalesce(r.model, '') ILIKE ?)"
            val like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(6) { args += like }
        }
        val statuses = f.statuses.filter { st -> RepairStatus.entries.any { it.name == st } }
        if (statuses.isNotEmpty()) { where += "r.status = ANY(?)"; args.add(statuses) }
        if (f.activeOnly) { where += "r.status = ANY(?)"; args.add(ACTIVE_STATUSES) }
        f.category?.takeIf { it.isNotBlank() }?.let { where += "r.device_category = ?"; args += it }
        f.from?.let { where += "r.created_at >= ?"; args += it.atStartOfDay(IST).toInstant() }
        f.to?.let { where += "r.created_at < ?"; args += it.plusDays(1).atStartOfDay(IST).toInstant() }
        f.partnerId?.let { where += "r.partner_id = ?"; args += it }
        f.shopId?.let { where += "r.shop_id = ?"; args += it }
        f.customerId?.let { where += "r.customer_id = ?"; args += it }
        val sql = summarySelect + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY r.created_at DESC, r.id LIMIT 5000"
        val all = query(sql, *args.toTypedArray(), map = ::summary).let { rows -> if (f.delayedOnly) rows.filter { it.delayed } else rows }
        RepairListResponse(all.drop(f.offset.coerceAtLeast(0)).take(f.limit.coerceIn(1, 200)), all.size)
    }

    // ==========================================================================================================
    // Detail
    // ==========================================================================================================

    fun detail(id: String): AdminRepairDetail = db.tx {
        val row = s.loadAny(this, id, lock = false)
        val sum = one("$summarySelect WHERE r.id = ?", id, map = ::summary)!!
        val ids = one("SELECT partner_id, shop_id, technician_id FROM repairs WHERE id = ?", id) {
            Triple(it.uuid("partner_id")?.toString(), it.uuid("shop_id")?.toString(), it.uuid("technician_id")?.toString())
        }!!
        AdminRepairDetail(
            repair = s.assemble(this, row.customerId, id),
            summary = sum,
            customer = customerSummary(this, row.customerId)!!,
            nextStatuses = nextStatuses(this, row),
            notes = query(
                "SELECT n.id, n.body, n.created_at, u.name, u.email FROM repair_internal_notes n JOIN admin_users u ON u.id = n.admin_id WHERE n.repair_id = ? ORDER BY n.id", id,
            ) { InternalNoteDto(it.getLong("id"), it.getString("body"), "${it.getString("name")} · ${it.getString("email")}", it.instant("created_at").toString()) },
            partnerId = ids.first, shopId = ids.second, technicianId = ids.third,
            quoteDraft = AdminOps(s).draftIn(this, id),
            claim = AdminOps(s).claimsIn(this, "c.claim_repair_id = ? OR c.original_repair_id = ?", listOf(id, id)).firstOrNull(),
            tickets = AdminOps(s).ticketsIn(this, "t.repair_id = ?", listOf(id)),
            refunds = AdminOps(s).refundsIn(this, id),
            audit = auditIn(this, AuditQuery(entityType = "repair", entityId = id, limit = 100)).items,
        )
    }

    /** Every next status the lifecycle allows, marked with whether the dashboard can perform it and why not. */
    private fun nextStatuses(tx: Tx, r: Services.Row): List<NextStatusDto> = r.status.allowedNext.map { to ->
        val (allowed, hint) = rule(tx, r, to)
        NextStatusDto(to.name, to.title, allowed, to in NEEDS_NOTE, hint)
    }

    private val NEEDS_NOTE = setOf(CANCELLED, PICKUP_FAILED, REPAIR_NOT_POSSIBLE, DIAGNOSED, REPAIRING)

    /** Whether staff may move [r] to [to] here, with the reason shown in the dashboard. */
    private fun rule(tx: Tx, r: Services.Row, to: RepairStatus): Pair<Boolean, String> {
        val ids = tx.one("SELECT partner_id, shop_id FROM repairs WHERE id = ?", r.id) { it.uuid(1) to it.uuid(2) }!!
        return when {
            to == PARTNER_ASSIGNED && ids.first == null -> false to "Assign a pickup partner first. Assigning one moves the repair here."
            to == PARTNER_ASSIGNED -> true to "Confirm the assigned pickup partner."
            to == PICKUP_SCHEDULED && r.status == PICKUP_FAILED -> false to "Use Reschedule to choose a new pickup window."
            to == AT_SHOP && ids.second == null -> false to "Assign a repair shop first."
            to == QUOTE_PENDING -> false to "Submit the quote from the Quotes section."
            r.status == QUOTE_PENDING && (to == REPAIRING || to == QUOTE_DECLINED) -> false to "Only the customer can approve or decline the quote."
            to == PAYMENT_COMPLETED -> false to "Recorded automatically when the customer pays."
            to == WARRANTY_ACTIVE -> false to "Starts automatically after payment."
            to == PAYMENT_PENDING && s.amountDue(tx, r) <= 0 -> false to "Nothing is due on this repair."
            to == PICKED_UP -> true to "Records the sealed pickup and a new seal code."
            to == RETURN_SCHEDULED -> true to "Reseals the device for return."
            to == DELIVERED -> true to "Records delivery; moves to payment if a quote was approved."
            to == DIAGNOSED -> true to "The note becomes the diagnosis summary."
            to == CANCELLED -> true to "Cancels before pickup at no charge. The note is the reason the customer sees."
            else -> true to to.message
        }
    }

    fun changeStatus(p: AdminPrincipal, id: String, req: StatusChangeRequest): AdminRepairDetail {
        p.require(Permission.REPAIRS_MANAGE)
        val to = RepairStatus.entries.firstOrNull { it.name == req.to } ?: throw badRequest("Unknown status ${req.to}.")
        val note = req.note.trim().take(300)
        db.tx {
            val r = s.loadAny(this, id)
            if (!canTransition(r.status, to)) throw conflict("invalid_transition", "This repair can't move from ${r.status.title} to ${to.title}.")
            val (allowed, hint) = rule(this, r, to)
            if (!allowed) throw conflict("not_allowed_here", hint)
            if (to in NEEDS_NOTE && note.length < 3) throw badRequest("Add a note explaining this change.")
            val shopName = one("SELECT s.name FROM repair_shops s JOIN repairs r ON r.shop_id = s.id WHERE r.id = ?", id) { it.getString(1) } ?: "Repair shop"
            val partnerName = one("SELECT p.name FROM pickup_partners p JOIN repairs r ON r.partner_id = p.id WHERE r.id = ?", id) { it.getString(1) } ?: "Pickup partner"
            val meta = mapOf("recordedBy" to "admin")
            when (to) {
                PARTNER_ASSIGNED -> {
                    val vehicle = one("SELECT p.vehicle FROM pickup_partners p JOIN repairs r ON r.partner_id = p.id WHERE r.id = ?", id) { it.getString(1) }
                    s.transition(this, id, r.status, to, "INTERNAL", STAFF_ACTOR, message = "$partnerName · $vehicle", metadata = meta)
                }
                PICKED_UP -> {
                    val seal = s.sealCode()
                    update("UPDATE repairs SET seal_code = ? WHERE id = ?", seal, id)
                    s.handover(this, id, "PICKUP", seal, partnerName, 0)
                    s.transition(this, id, r.status, to, "PARTNER", partnerName, title = "Device sealed and picked up",
                        message = "Seal $seal" + note.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty(), kind = "CUSTODY", metadata = meta + ("seal" to seal))
                }
                AT_SHOP -> {
                    val seal = one("SELECT seal_code FROM repairs WHERE id = ?", id) { it.getString(1) } ?: ""
                    s.handover(this, id, "SHOP_INTAKE", seal, shopName, 0)
                    s.transition(this, id, r.status, to, "SHOP", shopName, title = "Handed to repair shop", message = "Seal intact on arrival · $shopName", kind = "CUSTODY", metadata = meta)
                }
                DIAGNOSED -> {
                    update("INSERT INTO diagnoses (repair_id, summary, findings, diagnosed_at) VALUES (?,?,?,?) ON CONFLICT (repair_id) DO UPDATE SET summary = EXCLUDED.summary",
                        id, note, emptyList<String>(), s.now())
                    s.transition(this, id, r.status, to, "SHOP", shopName, message = note, metadata = meta)
                }
                RETURN_SCHEDULED -> {
                    val seal = s.sealCode()
                    update("UPDATE repairs SET seal_code = ? WHERE id = ?", seal, id)
                    s.handover(this, id, "RETURN", seal, shopName, 0)
                    s.transition(this, id, r.status, to, "SHOP", shopName, title = "Out for delivery", message = "Resealed with seal $seal", kind = "CUSTODY", metadata = meta)
                }
                DELIVERED -> s.deliver(this, r, partnerName)
                CANCELLED -> {
                    update("UPDATE repairs SET cancelled_at = ?, cancel_reason = ? WHERE id = ?", s.now(), note.take(120), id)
                    s.transition(this, id, r.status, to, "INTERNAL", STAFF_ACTOR, message = "${note.take(120)} · no charge", metadata = meta)
                    if (r.warrantyClaimFor != null) update(
                        "UPDATE warranty_claims SET status = 'RESOLVED', resolved_at = ?, decision_note = ? WHERE claim_repair_id = ? AND status IN ('OPEN', 'INFO_REQUESTED', 'APPROVED')",
                        s.now(), "Claim job cancelled: $note", id,
                    )
                }
                PICKUP_FAILED, REPAIR_NOT_POSSIBLE -> s.transition(this, id, r.status, to, "INTERNAL", STAFF_ACTOR, message = note, kind = "ALERT", metadata = meta)
                REPAIRING -> s.transition(this, id, r.status, to, "SHOP", shopName, title = "Back to repair", message = note, metadata = meta)
                else -> s.transition(this, id, r.status, to, "INTERNAL", STAFF_ACTOR, message = note.ifEmpty { to.message }, metadata = meta)
            }
            audit(this, p, "repair.status", "repair", id, note.ifEmpty { null }, mapOf("status" to r.status.name), mapOf("status" to to.name))
        }
        return detail(id)
    }

    // ==========================================================================================================
    // Assignment
    // ==========================================================================================================

    private val beforeShop = setOf(PICKUP_REQUESTED, PICKUP_SCHEDULED, PARTNER_ASSIGNED, PICKUP_FAILED, PICKED_UP)
    private val atShop = listOf(PICKED_UP, AT_SHOP, DIAGNOSED, QUOTE_PENDING, REPAIRING, QUALITY_CHECK).map { it.name }
    private val closed = setOf(DRAFT, CANCELLED, DELIVERED, PAYMENT_PENDING, PAYMENT_COMPLETED, WARRANTY_ACTIVE)

    /**
     * Assigns the pickup partner, the repair shop and/or the technician. Checks that each is active, has capacity, and
     * (for shops and technicians) handles this device. Assigning a partner to a scheduled pickup moves it to
     * PARTNER_ASSIGNED, the same step the customer sees.
     */
    fun assign(p: AdminPrincipal, id: String, req: AssignRequest): AdminRepairDetail {
        p.require(Permission.REPAIRS_MANAGE)
        if (req.partnerId.isNullOrBlank() && req.shopId.isNullOrBlank() && req.technicianId.isNullOrBlank()) throw badRequest("Choose a partner, shop or technician.")
        db.tx {
            val r = s.loadAny(this, id)
            if (r.status in closed) throw conflict("assign_not_allowed", "This repair is ${r.status.title.lowercase()}, so assignments can't change.")
            val current = one("SELECT partner_id, shop_id, technician_id, slot_date FROM repairs WHERE id = ?", id) {
                listOf(it.uuid("partner_id"), it.uuid("shop_id"), it.uuid("technician_id"), it.date("slot_date"))
            }!!
            val before = mapOf("partnerId" to current[0]?.toString(), "shopId" to current[1]?.toString(), "technicianId" to current[2]?.toString())
            var shop = current[1] as UUID?

            req.partnerId?.takeIf { it.isNotBlank() }?.let { raw ->
                val pid = parseUuid(raw, "partnerId")
                val partner = one("SELECT name, vehicle, active, daily_capacity FROM pickup_partners WHERE id = ?", pid) {
                    listOf(it.getString(1), it.getString(2), it.getBoolean(3), it.getInt(4))
                } ?: throw notFound("Pickup partner not found.")
                if (partner[2] != true) throw conflict("partner_inactive", "${partner[0]} is inactive.")
                val load = one(
                    "SELECT count(*) FROM repairs WHERE partner_id = ? AND slot_date = ? AND id <> ? AND status = ANY(?)",
                    pid, current[3], id, listOf(PICKUP_SCHEDULED, PARTNER_ASSIGNED, PICKUP_FAILED).map { it.name },
                ) { it.getInt(1) }!!
                if (load >= partner[3] as Int) throw conflict("partner_full", "${partner[0]} already has $load pickups on ${current[3]} (capacity ${partner[3]}).")
                update("UPDATE repairs SET partner_id = ?, updated_at = ? WHERE id = ?", pid, s.now(), id)
                if (r.status == PICKUP_SCHEDULED) s.transition(this, id, r.status, PARTNER_ASSIGNED, "INTERNAL", STAFF_ACTOR, message = "${partner[0]} · ${partner[1]}")
                else if (current[0] != pid) s.event(this, id, null, "INTERNAL", STAFF_ACTOR, title = "Pickup partner updated", message = "${partner[0]} · ${partner[1]}", kind = "NOTE")
            }

            req.shopId?.takeIf { it.isNotBlank() }?.let { raw ->
                val sid = parseUuid(raw, "shopId")
                if (r.status !in beforeShop) throw conflict("shop_locked", "The device is already at the shop; the shop can't change now.")
                val sh = one("SELECT name, active, capacity, capabilities FROM repair_shops WHERE id = ?", sid) {
                    listOf(it.getString(1), it.getBoolean(2), it.getInt(3), it.strings("capabilities"))
                } ?: throw notFound("Repair shop not found.")
                if (sh[1] != true) throw conflict("shop_inactive", "${sh[0]} is inactive.")
                @Suppress("UNCHECKED_CAST")
                if (r.category !in (sh[3] as List<String>)) throw conflict("shop_not_capable", "${sh[0]} doesn't repair ${r.category.lowercase()} devices.")
                val load = one("SELECT count(*) FROM repairs WHERE shop_id = ? AND id <> ? AND status = ANY(?)", sid, id, atShop) { it.getInt(1) }!!
                if (load >= sh[2] as Int) throw conflict("shop_full", "${sh[0]} is at capacity ($load of ${sh[2]} jobs).")
                if (shop != sid) update("UPDATE repairs SET shop_id = ?, technician_id = NULL, updated_at = ? WHERE id = ?", sid, s.now(), id)
                shop = sid
            }

            req.technicianId?.takeIf { it.isNotBlank() }?.let { raw ->
                val tid = parseUuid(raw, "technicianId")
                val t = one("SELECT name, shop_id, active, skills FROM technicians WHERE id = ?", tid) {
                    listOf(it.getString(1), it.uuid(2), it.getBoolean(3), it.strings("skills"))
                } ?: throw notFound("Technician not found.")
                if (shop == null) throw conflict("no_shop", "Assign a repair shop before a technician.")
                if (t[1] != shop) throw conflict("technician_other_shop", "${t[0]} works at a different shop.")
                if (t[2] != true) throw conflict("technician_inactive", "${t[0]} is inactive.")
                @Suppress("UNCHECKED_CAST") val skills = t[3] as List<String>
                if (skills.isNotEmpty() && r.category !in skills) throw conflict("technician_not_capable", "${t[0]} isn't set up for ${r.category.lowercase()} repairs.")
                update("UPDATE repairs SET technician_id = ?, updated_at = ? WHERE id = ?", tid, s.now(), id)
            }

            val after = one("SELECT partner_id, shop_id, technician_id FROM repairs WHERE id = ?", id) {
                mapOf("partnerId" to it.uuid(1)?.toString(), "shopId" to it.uuid(2)?.toString(), "technicianId" to it.uuid(3)?.toString())
            }!!
            audit(this, p, "repair.assign", "repair", id, req.reason.trim().ifEmpty { null }, before, after)
        }
        return detail(id)
    }

    // ==========================================================================================================
    // Reschedule, cancel, notes
    // ==========================================================================================================

    /** Staff can move a pickup up to 30 days ahead, into any open window with capacity. */
    fun reschedule(p: AdminPrincipal, id: String, req: AdminRescheduleRequest): AdminRepairDetail {
        p.require(Permission.REPAIRS_MANAGE)
        val reason = req.reason.trim()
        if (reason.length < 3) throw badRequest("Add a reason for the new pickup window.")
        val date = parseDate(req.date)
        db.tx {
            val r = s.loadAny(this, id)
            if (!r.status.beforePickup || r.status == CANCELLED || r.status == DRAFT)
                throw conflict("reschedule_not_allowed", "Only pickups that haven't happened can be rescheduled.")
            val today = s.todayIst()
            if (date.isBefore(today) || date.isAfter(today.plusDays(30))) throw unprocessable("slot_unavailable", "Choose a date in the next 30 days.")
            val capacity = one("SELECT capacity FROM pickup_slot_templates WHERE start_hour = ?", req.startHour) { it.getInt(1) }
                ?: throw unprocessable("slot_unavailable", "There's no pickup window at that hour.")
            if (date == today && req.startHour <= s.hourIst()) throw unprocessable("slot_unavailable", "That window has already started.")
            one("SELECT reason FROM pickup_slot_blocks WHERE slot_date = ? AND start_hour = ?", date, req.startHour) { it.getString(1) }
                ?.let { throw conflict("slot_blocked", "That window is blocked: $it.") }
            val booked = one("SELECT count(*) FROM repairs WHERE slot_date = ? AND slot_start_hour = ? AND id <> ? AND status = ANY(?)",
                date, req.startHour, id, RepairStatus.entries.filter { it.beforePickup && it != PICKUP_FAILED }.map { it.name }) { it.getInt(1) }!!
            if (booked >= capacity) throw conflict("slot_full", "That window is full ($booked of $capacity).")
            val old = one("SELECT slot_date, slot_start_hour FROM repairs WHERE id = ?", id) { it.date(1).toString() to it.getInt(2) }!!
            update("UPDATE repairs SET slot_date = ?, slot_start_hour = ?, updated_at = ? WHERE id = ?", date, req.startHour, s.now(), id)
            val label = s.slotLabel(date, req.startHour)
            if (r.status == PICKUP_FAILED) s.transition(this, id, r.status, PICKUP_SCHEDULED, "INTERNAL", STAFF_ACTOR, message = "New window $label")
            else s.event(this, id, null, "INTERNAL", STAFF_ACTOR, title = "Pickup rescheduled", message = label)
            audit(this, p, "repair.reschedule", "repair", id, reason,
                mapOf("slotDate" to old.first, "slotStartHour" to old.second), mapOf("slotDate" to date.toString(), "slotStartHour" to req.startHour))
        }
        return detail(id)
    }

    fun cancel(p: AdminPrincipal, id: String, reason: String) = changeStatus(p, id, StatusChangeRequest(CANCELLED.name, reason))

    fun addNote(p: AdminPrincipal, id: String, body: String): AdminRepairDetail {
        p.require(Permission.REPAIRS_NOTE)
        val text = body.trim()
        if (text.isEmpty() || text.length > 2000) throw badRequest("Notes are 1 to 2,000 characters.")
        db.tx {
            s.loadAny(this, id, lock = false)
            update("INSERT INTO repair_internal_notes (repair_id, admin_id, body, created_at) VALUES (?,?,?,?)", id, p.id, text, s.now())
            audit(this, p, "repair.note", "repair", id, null, null, mapOf("note" to text))
        }
        return detail(id)
    }

    // ==========================================================================================================
    // Customers
    // ==========================================================================================================

    private val customerSelect = """
        SELECT c.*, count(r.id) AS repairs, count(r.id) FILTER (WHERE r.status = ANY(?)) AS active_repairs, max(r.created_at) AS last_repair
        FROM customers c LEFT JOIN repairs r ON r.customer_id = c.id
    """.trimIndent()

    private fun customerRow(rs: ResultSet) = CustomerSummary(
        rs.uuid("id").toString(), rs.getString("kind"), rs.getString("display_name"), rs.getString("phone"), rs.getString("email"),
        rs.instant("created_at").toString(), rs.getInt("repairs"), rs.getInt("active_repairs"), rs.instant("last_repair")?.toString(),
    )

    internal fun customerSummary(tx: Tx, id: UUID): CustomerSummary? =
        tx.one("$customerSelect WHERE c.id = ? GROUP BY c.id", ACTIVE_STATUSES, id, map = ::customerRow)

    fun customers(q: String?, limit: Int, offset: Int): CustomerListResponse = db.tx {
        val args = mutableListOf<Any?>(ACTIVE_STATUSES)
        val where = q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val like = "%" + it.replace("%", "\\%").replace("_", "\\_") + "%"
            args.addAll(listOf(like, like, like, like))
            " WHERE c.merged_into_id IS NULL AND (coalesce(c.display_name, '') ILIKE ? OR coalesce(c.phone, '') LIKE ? OR c.id::text ILIKE ? OR coalesce(c.email, '') ILIKE ?)"
        } ?: " WHERE c.merged_into_id IS NULL"
        val all = query("$customerSelect$where GROUP BY c.id ORDER BY max(r.created_at) DESC NULLS LAST, c.created_at DESC", *args.toTypedArray(), map = ::customerRow)
        CustomerListResponse(all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 200)), all.size)
    }

    fun customer(id: String): CustomerDetail = db.tx {
        val cid = parseUuid(id, "customerId")
        val c = customerSummary(this, cid) ?: throw notFound("Customer not found.")
        CustomerDetail(
            customer = c,
            repairs = query("$summarySelect WHERE r.customer_id = ? ORDER BY r.created_at DESC", cid, map = ::summary),
            addresses = query("SELECT * FROM addresses WHERE customer_id = ? ORDER BY deleted_at NULLS FIRST, is_default DESC, created_at", cid) {
                AdminAddressDto(s.addressDto(it), it.instant("deleted_at") != null)
            },
            claims = AdminOps(s).claimsIn(this, "c.customer_id = ?", listOf(cid)),
            tickets = AdminOps(s).ticketsIn(this, "t.customer_id = ?", listOf(cid)),
        )
    }

    // ==========================================================================================================
    // Overview
    // ==========================================================================================================

    fun overview(): OverviewDto = db.tx {
        val today = s.todayIst()
        val startToday = today.atStartOfDay(IST).toInstant()
        fun count(sql: String, vararg args: Any?) = one(sql, *args) { it.getInt(1) } ?: 0
        fun sum(sql: String, vararg args: Any?) = one(sql, *args) { it.getLong(1).toInt() } ?: 0
        val active = query("$summarySelect WHERE r.status = ANY(?) ORDER BY r.updated_at", ACTIVE_STATUSES, map = ::summary)
        val beforePickup = listOf(PICKUP_REQUESTED, PICKUP_SCHEDULED, PARTNER_ASSIGNED).map { it.name }
        OverviewDto(
            today = today.toString(),
            bookingsToday = count("SELECT count(*) FROM repairs WHERE created_at >= ?", startToday),
            bookings7d = count("SELECT count(*) FROM repairs WHERE created_at >= ?", today.minusDays(6).atStartOfDay(IST).toInstant()),
            activeRepairs = active.size,
            pickupsToday = count("SELECT count(*) FROM repairs WHERE slot_date = ? AND status = ANY(?)", today, beforePickup),
            deliveriesToday = count("SELECT count(*) FROM repairs WHERE status = 'RETURN_SCHEDULED'"),
            pendingQuotes = count("SELECT count(*) FROM repairs WHERE status = 'QUOTE_PENDING'"),
            draftQuotes = count("SELECT count(*) FROM quote_drafts d JOIN repairs r ON r.id = d.repair_id WHERE r.status IN ('AT_SHOP', 'DIAGNOSED')"),
            delayedRepairs = active.count { it.delayed },
            revenueToday = sum("SELECT coalesce(sum(amount_inr), 0) FROM payment_records WHERE recorded_at >= ?", startToday),
            revenue7d = sum("SELECT coalesce(sum(amount_inr), 0) FROM payment_records WHERE recorded_at >= ?", today.minusDays(6).atStartOfDay(IST).toInstant()),
            revenue30d = sum("SELECT coalesce(sum(amount_inr), 0) FROM payment_records WHERE recorded_at >= ?", today.minusDays(29).atStartOfDay(IST).toInstant()),
            refunds30d = sum("SELECT coalesce(sum(amount_inr), 0) FROM payment_refunds WHERE created_at >= ?", today.minusDays(29).atStartOfDay(IST).toInstant()),
            unreconciledPayments = count("SELECT count(*) FROM payment_records WHERE reconciled_at IS NULL"),
            openClaims = count("SELECT count(*) FROM warranty_claims WHERE status IN ('OPEN', 'INFO_REQUESTED', 'APPROVED')"),
            openTickets = count("SELECT count(*) FROM support_tickets WHERE status = 'OPEN'"),
            unassignedTickets = count("SELECT count(*) FROM support_tickets WHERE status = 'OPEN' AND assigned_to IS NULL"),
            statusCounts = query("SELECT status, count(*) FROM repairs GROUP BY status") { it.getString(1) to it.getInt(2) }.toMap(),
            delayed = active.filter { it.delayed }.take(10),
            recentActivity = auditIn(this, AuditQuery(limit = 12)).items,
        )
    }

    // ==========================================================================================================
    // Audit log (read side)
    // ==========================================================================================================

    data class AuditQuery(
        val entityType: String? = null, val entityId: String? = null, val adminEmail: String? = null, val action: String? = null,
        val before: Long? = null, val limit: Int = 50,
    )

    fun auditLog(q: AuditQuery): AuditResponse = db.tx { auditIn(this, q) }

    internal fun auditIn(tx: Tx, q: AuditQuery): AuditResponse = with(tx) {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        q.entityType?.takeIf { it.isNotBlank() }?.let { where += "entity_type = ?"; args += it }
        q.entityId?.takeIf { it.isNotBlank() }?.let { where += "entity_id = ?"; args += it }
        q.adminEmail?.takeIf { it.isNotBlank() }?.let { where += "admin_email ILIKE ?"; args += "%$it%" }
        q.action?.takeIf { it.isNotBlank() }?.let { where += "action LIKE ?"; args += "$it%" }
        q.before?.let { where += "id < ?"; args += it }
        val limit = q.limit.coerceIn(1, 200)
        val items = query(
            "SELECT * FROM admin_audit_log" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY id DESC LIMIT ${limit + 1}",
            *args.toTypedArray(),
        ) {
            AuditEntryDto(
                it.getLong("id"), it.getString("admin_email"), it.getString("action"), it.getString("entity_type"), it.getString("entity_id"),
                it.getString("reason"), it.getString("before")?.let(::parseJson), it.getString("after")?.let(::parseJson), it.instant("occurred_at").toString(),
            )
        }
        AuditResponse(items.take(limit), if (items.size > limit) items[limit - 1].id else null)
    }

    private fun parseJson(raw: String): JsonElement = json.parseToJsonElement(raw)
}
