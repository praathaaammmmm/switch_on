package com.switchon.api

import com.switchon.api.RepairStatus.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.sql.ResultSet
import java.util.UUID

/** Quotes, payments, warranty claims, support and the team. Every change is audited in its own transaction. */
class AdminOps(private val s: Services) {
    private val db = s.db

    // ==========================================================================================================
    // Quotes
    // ==========================================================================================================

    internal fun draftIn(tx: Tx, repairId: String): QuoteDraftDto? = tx.one(
        "SELECT d.*, u.email FROM quote_drafts d JOIN admin_users u ON u.id = d.updated_by WHERE d.repair_id = ?", repairId,
    ) {
        val items = json.decodeFromString(ListSerializer(LineItemDto.serializer()), it.getString("items"))
        QuoteDraftDto(repairId, it.getString("diagnosis"), it.strings("findings"), items, it.getString("warranty_text"), items.sumOf { i -> i.amount },
            it.getString("email"), it.instant("updated_at").toString())
    }

    /** Quotes in every state: drafts still being prepared, then sent quotes by the customer's decision. */
    fun quotes(state: String?): List<QuoteListItem> = db.tx {
        val device = "r.device_category || ' · ' || r.issue_code || coalesce(' · ' || r.brand, '') || coalesce(' ' || r.model, '')"
        val drafts = if (state == null || state == "DRAFT") query(
            """SELECT d.*, r.estimate_low_inr, r.estimate_high_inr, r.contact_name, r.status, $device AS device
               FROM quote_drafts d JOIN repairs r ON r.id = d.repair_id ORDER BY d.updated_at DESC""",
        ) {
            val items = json.decodeFromString(ListSerializer(LineItemDto.serializer()), it.getString("items"))
            QuoteListItem(it.getString("repair_id"), "DRAFT", items.sumOf { i -> i.amount }, items.size, it.getInt("estimate_low_inr"), it.getInt("estimate_high_inr"),
                it.getString("contact_name"), it.getString("device"), it.getString("status"), it.instant("updated_at").toString(), null, null)
        } else emptyList()
        val sent = if (state == "DRAFT") emptyList() else {
            val filter = when (state) {
                null -> ""
                "PENDING" -> " WHERE q.decision IS NULL"
                "APPROVED", "DECLINED" -> " WHERE q.decision = '$state'"
                else -> throw badRequest("state must be DRAFT, PENDING, APPROVED or DECLINED.")
            }
            query(
                """SELECT q.*, r.estimate_low_inr, r.estimate_high_inr, r.contact_name, r.status, $device AS device,
                          (SELECT count(*) FROM quote_line_items l WHERE l.quote_id = q.id) AS item_count
                   FROM quotes q JOIN repairs r ON r.id = q.repair_id$filter ORDER BY q.sent_at DESC""",
            ) {
                QuoteListItem(it.getString("repair_id"), it.getString("decision") ?: "PENDING", it.getInt("total_inr"), it.getInt("item_count"),
                    it.getInt("estimate_low_inr"), it.getInt("estimate_high_inr"), it.getString("contact_name"), it.getString("device"), it.getString("status"),
                    it.instant("sent_at").toString(), it.instant("decided_at")?.toString(), it.getString("decline_reason"))
            }
        }
        drafts + sent
    }

    private fun validItems(items: List<LineItemDto>): List<LineItemDto> {
        if (items.size > 20) throw badRequest("A quote can have up to 20 lines.")
        return items.map { l ->
            if (l.label.isBlank() || l.label.length > 80) throw badRequest("Every line needs a label of up to 80 characters.")
            if (l.kind !in setOf("PART", "LABOUR", "FEE")) throw badRequest("Line kinds are PART, LABOUR or FEE.")
            if (l.amount !in 0..500_000) throw badRequest("Line amounts are between ₹0 and ₹5,00,000.")
            l.copy(label = l.label.trim(), detail = l.detail.trim().take(80))
        }
    }

    /** Saves the shop's draft. Only while the device is at the shop and no quote has been sent. */
    fun saveDraft(p: AdminPrincipal, repairId: String, req: QuoteDraftRequest): QuoteDraftDto {
        p.require(Permission.QUOTES_MANAGE)
        val items = validItems(req.items)
        if (req.diagnosis.length > 300) throw badRequest("Keep the diagnosis under 300 characters.")
        return db.tx {
            val r = s.loadAny(this, repairId)
            if (r.status !in setOf(AT_SHOP, DIAGNOSED)) throw conflict("quote_not_editable", "Quotes are prepared while the device is at the shop (status is ${r.status.title}).")
            val before = draftIn(this, repairId)
            update(
                """INSERT INTO quote_drafts (repair_id, diagnosis, findings, items, warranty_text, updated_by, updated_at) VALUES (?,?,?,?::jsonb,?,?,?)
                   ON CONFLICT (repair_id) DO UPDATE SET diagnosis = EXCLUDED.diagnosis, findings = EXCLUDED.findings, items = EXCLUDED.items,
                   warranty_text = EXCLUDED.warranty_text, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at""",
                repairId, req.diagnosis.trim(), req.findings.map { it.trim() }.filter { it.isNotEmpty() }.take(10),
                Json(json.encodeToString(items)), req.warrantyText.trim().ifEmpty { "90 days on parts and labour" }.take(80), p.id, s.now(),
            )
            audit(this, p, "quote.draft_save", "repair", repairId, null, before?.let { mapOf("total" to it.total, "lines" to it.items.size) },
                mapOf("total" to items.sumOf { it.amount }, "lines" to items.size, "diagnosis" to req.diagnosis.trim()))
            draftIn(this, repairId)!!
        }
    }

    /**
     * Sends the draft to the customer: records the diagnosis (moving AT_SHOP → DIAGNOSED if needed), then the quote
     * (DIAGNOSED → QUOTE_PENDING) through the same lifecycle the internal tools use. The customer decides in the app.
     */
    fun submitQuote(p: AdminPrincipal, repairId: String): AdminRepairDetail {
        p.require(Permission.QUOTES_MANAGE)
        db.tx {
            var r = s.loadAny(this, repairId)
            val draft = draftIn(this, repairId) ?: throw conflict("no_draft", "Save a draft before submitting.")
            if (draft.diagnosis.isBlank()) throw badRequest("Add the diagnosis before sending the quote.")
            if (draft.items.isEmpty()) throw badRequest("Add at least one line to the quote.")
            if (one("SELECT 1 FROM quotes WHERE repair_id = ?", repairId) { 1 } != null) throw conflict("quote_exists", "A quote was already sent for this repair.")
            val shop = one("SELECT s.name FROM repair_shops s JOIN repairs r ON r.shop_id = s.id WHERE r.id = ?", repairId) { it.getString(1) } ?: "Repair shop"
            update(
                "INSERT INTO diagnoses (repair_id, summary, findings, diagnosed_at) VALUES (?,?,?,?) ON CONFLICT (repair_id) DO UPDATE SET summary = EXCLUDED.summary, findings = EXCLUDED.findings",
                repairId, draft.diagnosis, draft.findings, s.now(),
            )
            if (r.status == AT_SHOP) {
                s.transition(this, repairId, r.status, DIAGNOSED, "SHOP", shop, message = draft.diagnosis)
                r = s.loadAny(this, repairId)
            }
            if (r.status != DIAGNOSED) throw conflict("invalid_transition", "Quotes are sent after diagnosis (status is ${r.status.title}).")
            s.sendQuote(this, r, draft.items, shop)
            update("UPDATE quotes SET warranty_text = ? WHERE repair_id = ?", draft.warrantyText, repairId)
            update("DELETE FROM quote_drafts WHERE repair_id = ?", repairId)
            audit(this, p, "quote.submit", "repair", repairId, null, mapOf("status" to r.status.name),
                mapOf("status" to QUOTE_PENDING.name, "total" to draft.total, "lines" to draft.items.size, "estimateLow" to r.estimateLow, "estimateHigh" to r.estimateHigh))
        }
        return AdminRepairs(s).detail(repairId)
    }

    // ==========================================================================================================
    // Payments, invoices, refunds
    // ==========================================================================================================

    internal fun refundsIn(tx: Tx, repairId: String): List<RefundDto> = tx.query(
        "SELECT f.*, u.email FROM payment_refunds f JOIN admin_users u ON u.id = f.created_by WHERE f.repair_id = ? ORDER BY f.created_at", repairId,
    ) { RefundDto(it.uuid("id").toString(), it.getInt("amount_inr"), it.getString("reason"), it.getString("email"), it.instant("created_at").toString()) }

    private val paymentSelect = """
        SELECT p.*, r.contact_name, i.invoice_no, u.email AS reconciler,
               coalesce((SELECT sum(amount_inr) FROM payment_refunds f WHERE f.repair_id = p.repair_id), 0) AS refunded
        FROM payment_records p JOIN repairs r ON r.id = p.repair_id
        LEFT JOIN invoices i ON i.repair_id = p.repair_id LEFT JOIN admin_users u ON u.id = p.reconciled_by
    """.trimIndent()

    private fun paymentRow(rs: ResultSet): PaymentAdminDto {
        val amount = rs.getInt("amount_inr"); val refunded = rs.getInt("refunded")
        return PaymentAdminDto(rs.getString("repair_id"), rs.getString("method"), amount, rs.getString("reference"), rs.instant("recorded_at").toString(),
            refunded, amount - refunded, rs.instant("reconciled_at")?.toString(), rs.getString("reconciler"), rs.getString("reconciled_note"),
            rs.getString("invoice_no"), rs.getString("contact_name"))
    }

    fun payments(unreconciledOnly: Boolean, method: String?): PaymentsResponse = db.tx {
        val where = mutableListOf<String>(); val args = mutableListOf<Any?>()
        if (unreconciledOnly) where += "p.reconciled_at IS NULL"
        method?.takeIf { it.isNotBlank() }?.let { where += "p.method = ?"; args += it.uppercase() }
        val items = query(paymentSelect + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY p.recorded_at DESC",
            *args.toTypedArray(), map = ::paymentRow)
        val all = if (where.isEmpty()) items else query(paymentSelect, map = ::paymentRow)
        PaymentsResponse(items, PaymentTotals(all.sumOf { it.amount }, all.sumOf { it.refunded }, all.sumOf { it.net }, all.count { it.reconciledAt == null }))
    }

    fun invoice(repairId: String): AdminInvoiceDto = db.tx {
        val r = s.loadAny(this, repairId, lock = false)
        val payment = one("$paymentSelect WHERE p.repair_id = ?", repairId, map = ::paymentRow) ?: throw notFound("There's no payment on this repair.")
        AdminInvoiceDto(s.invoice(r.customerId, repairId), payment, refundsIn(this, repairId))
    }

    /** Records a refund against the payment. No gateway: finance makes the actual refund in the payment provider. */
    fun refund(p: AdminPrincipal, repairId: String, req: RefundRequest): AdminInvoiceDto {
        p.require(Permission.PAYMENTS_MANAGE)
        val reason = req.reason.trim()
        if (reason.length < 5) throw badRequest("Give a reason of at least 5 characters.", "reason_required")
        if (req.amount <= 0) throw badRequest("Refund amount must be more than ₹0.")
        db.tx {
            val paid = one("SELECT amount_inr FROM payment_records WHERE repair_id = ? FOR UPDATE", repairId) { it.getInt(1) } ?: throw notFound("There's no payment on this repair.")
            val refunded = one("SELECT coalesce(sum(amount_inr), 0) FROM payment_refunds WHERE repair_id = ?", repairId) { it.getLong(1).toInt() }!!
            if (req.amount > paid - refunded) throw unprocessable("refund_too_large", "At most ₹${s.inr(paid - refunded)} can still be refunded on this payment.")
            update("INSERT INTO payment_refunds (id, repair_id, amount_inr, reason, created_by, created_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), repairId, req.amount, reason.take(300), p.id, s.now())
            s.event(this, repairId, null, "INTERNAL", STAFF_ACTOR, title = "Refund issued", message = "₹${s.inr(req.amount)} to your original payment method",
                kind = "PAYMENT", metadata = mapOf("refund" to req.amount.toString()))
            audit(this, p, "payment.refund", "payment", repairId, reason, mapOf("refunded" to refunded), mapOf("refunded" to refunded + req.amount, "amount" to req.amount))
        }
        return invoice(repairId)
    }

    fun reconcile(p: AdminPrincipal, repairId: String, req: ReconcileRequest): AdminInvoiceDto {
        p.require(Permission.PAYMENTS_MANAGE)
        db.tx {
            val already = one("SELECT reconciled_at FROM payment_records WHERE repair_id = ? FOR UPDATE", repairId) { it.getObject(1) != null }
                ?: throw notFound("There's no payment on this repair.")
            if (already) throw conflict("already_reconciled", "This payment is already reconciled.")
            update("UPDATE payment_records SET reconciled_at = ?, reconciled_by = ?, reconciled_note = ? WHERE repair_id = ?", s.now(), p.id, req.note.trim().take(200).ifEmpty { null }, repairId)
            audit(this, p, "payment.reconcile", "payment", repairId, req.note.trim().ifEmpty { null }, mapOf("reconciled" to false), mapOf("reconciled" to true))
        }
        return invoice(repairId)
    }

    // ==========================================================================================================
    // Warranty claims
    // ==========================================================================================================

    private val liveClaim = setOf("OPEN", "INFO_REQUESTED", "APPROVED")

    internal fun claimsIn(tx: Tx, where: String?, args: List<Any?>): List<AdminClaimDto> = tx.query(
        """SELECT c.*, cu.display_name, j.status AS job_status, w.ends_on
           FROM warranty_claims c JOIN customers cu ON cu.id = c.customer_id JOIN repairs j ON j.id = c.claim_repair_id
           LEFT JOIN warranties w ON w.repair_id = c.original_repair_id""" + (where?.let { " WHERE $it" } ?: "") + " ORDER BY c.opened_at DESC",
        *args.toTypedArray(),
    ) {
        AdminClaimDto(it.uuid("id").toString(), it.uuid("customer_id").toString(), it.getString("display_name"), it.getString("original_repair_id"),
            it.getString("claim_repair_id"), it.getString("job_status"), it.getString("problem"), it.getString("detail"), it.getString("status"),
            it.getString("decision_note"), it.instant("opened_at").toString(), it.instant("resolved_at")?.toString(), it.date("ends_on")?.toString())
    }

    fun claims(status: String?): List<AdminClaimDto> = db.tx {
        if (status.isNullOrBlank()) claimsIn(this, null, emptyList()) else claimsIn(this, "c.status = ?", listOf(status))
    }

    private fun claimFor(tx: Tx, id: String): AdminClaimDto =
        claimsIn(tx, "c.id = ?", listOf(parseUuid(id, "claimId"))).firstOrNull() ?: throw notFound("Claim not found.")

    fun claimAction(p: AdminPrincipal, id: String, action: String, req: ClaimActionRequest): AdminClaimDto {
        p.require(Permission.CLAIMS_MANAGE)
        val note = req.note.trim().take(300)
        return db.tx {
            // Lock the claim row first, then read it, so two reviewers can't act on the same state.
            one("SELECT 1 FROM warranty_claims WHERE id = ? FOR UPDATE", parseUuid(id, "claimId")) { 1 } ?: throw notFound("Claim not found.")
            val c = claimFor(this, id)
            val job = c.claimRepairId
            val jobStatus = RepairStatus.valueOf(c.claimRepairStatus)
            val next: String = when (action) {
                "approve" -> {
                    if (c.status !in setOf("OPEN", "INFO_REQUESTED")) throw conflict("claim_state", "Only open claims can be approved (this one is ${c.status.lowercase()}).")
                    s.event(this, job, null, "INTERNAL", STAFF_ACTOR, title = "Warranty claim approved", message = note.ifEmpty { "Covered under your 90-day warranty." }, kind = "NOTE")
                    "APPROVED"
                }
                "request-info" -> {
                    if (c.status !in setOf("OPEN", "APPROVED")) throw conflict("claim_state", "Information can be requested on open claims only.")
                    if (note.length < 5) throw badRequest("Write the question the customer will see.")
                    s.event(this, job, null, "INTERNAL", STAFF_ACTOR, title = "More information needed", message = note, kind = "ALERT")
                    "INFO_REQUESTED"
                }
                "reject" -> {
                    if (c.status !in liveClaim) throw conflict("claim_state", "This claim is already ${c.status.lowercase()}.")
                    if (note.length < 5) throw badRequest("Give the reason the customer will see.")
                    val r = s.loadAny(this, job)
                    when {
                        jobStatus.beforePickup && jobStatus != CANCELLED -> {
                            update("UPDATE repairs SET cancelled_at = ?, cancel_reason = ? WHERE id = ?", s.now(), "Warranty claim not approved", job)
                            s.transition(this, job, jobStatus, CANCELLED, "INTERNAL", STAFF_ACTOR, title = "Warranty claim not approved", message = note)
                        }
                        canTransition(jobStatus, REPAIR_NOT_POSSIBLE) ->
                            s.transition(this, job, r.status, REPAIR_NOT_POSSIBLE, "INTERNAL", STAFF_ACTOR, title = "Not covered by warranty",
                                message = "$note · returned to you free", kind = "ALERT")
                        jobStatus == CANCELLED -> Unit
                        else -> throw conflict("claim_state", "The claim job is ${jobStatus.title.lowercase()}; reject it once it reaches the shop.")
                    }
                    "REJECTED"
                }
                "resolve" -> {
                    if (c.status !in liveClaim) throw conflict("claim_state", "This claim is already ${c.status.lowercase()}.")
                    if (jobStatus !in setOf(DELIVERED, CANCELLED)) throw conflict("claim_state", "Resolve after the claim job is delivered or cancelled (it's ${jobStatus.title.lowercase()}).")
                    "RESOLVED"
                }
                else -> throw notFound()
            }
            val closing = next in setOf("REJECTED", "RESOLVED")
            update("UPDATE warranty_claims SET status = ?, decision_note = ?, resolved_at = CASE WHEN ? THEN ?::timestamptz ELSE resolved_at END WHERE id = ?",
                next, note.ifEmpty { c.decisionNote }, closing, s.now(), UUID.fromString(c.id))
            if (closing) s.event(this, c.originalRepairId, null, "INTERNAL", STAFF_ACTOR,
                title = if (next == "REJECTED") "Warranty claim not approved" else "Warranty claim resolved", message = note.ifEmpty { "Job $job" }, kind = "NOTE")
            audit(this, p, "claim.$action", "claim", c.id, note.ifEmpty { null }, mapOf("status" to c.status), mapOf("status" to next))
            claimFor(this, id)
        }
    }

    // ==========================================================================================================
    // Support
    // ==========================================================================================================

    internal fun ticketsIn(tx: Tx, where: String?, args: List<Any?>): List<AdminTicketSummary> = tx.query(
        """SELECT t.*, c.display_name, u.name AS assignee, (SELECT count(*) FROM ticket_messages m WHERE m.ticket_id = t.id) AS messages
           FROM support_tickets t JOIN customers c ON c.id = t.customer_id LEFT JOIN admin_users u ON u.id = t.assigned_to""" +
            (where?.let { " WHERE $it" } ?: "") + " ORDER BY t.status DESC, t.updated_at DESC",
        *args.toTypedArray(),
    ) {
        AdminTicketSummary(it.getString("id"), it.uuid("customer_id").toString(), it.getString("display_name"), it.getString("repair_id"), it.getString("topic"),
            it.getString("status"), it.uuid("assigned_to")?.toString(), it.getString("assignee"), it.instant("created_at").toString(),
            it.instant("updated_at").toString(), it.getInt("messages"))
    }

    fun tickets(status: String?, assigned: String?, q: String?, me: AdminPrincipal): List<AdminTicketSummary> = db.tx {
        val where = mutableListOf<String>(); val args = mutableListOf<Any?>()
        status?.takeIf { it.isNotBlank() }?.let { where += "t.status = ?"; args += it }
        when (assigned) {
            null, "" -> Unit
            "me" -> { where += "t.assigned_to = ?"; args += me.id }
            "unassigned" -> where += "t.assigned_to IS NULL"
            else -> { where += "t.assigned_to = ?"; args += parseUuid(assigned, "assigned") }
        }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "(t.id ILIKE ? OR t.topic ILIKE ? OR t.detail ILIKE ? OR coalesce(t.repair_id, '') ILIKE ? OR coalesce(c.display_name, '') ILIKE ?)"
            repeat(5) { _ -> args += "%$it%" }
        }
        ticketsIn(this, where.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args)
    }

    fun ticket(id: String): AdminTicketDetail = db.tx { ticketIn(this, id) }

    private fun ticketIn(tx: Tx, id: String): AdminTicketDetail = with(tx) {
        val t = ticketsIn(this, "t.id = ?", listOf(id)).firstOrNull() ?: throw notFound("Ticket not found.")
        AdminTicketDetail(
            t, one("SELECT detail FROM support_tickets WHERE id = ?", id) { it.getString(1) }!!,
            query("SELECT m.*, u.name FROM ticket_messages m LEFT JOIN admin_users u ON u.id = m.admin_id WHERE m.ticket_id = ? ORDER BY m.id", id) {
                TicketMessageDto(it.getLong("id"), it.getString("visibility"), it.getString("body"), it.getString("name") ?: "Customer", it.instant("created_at").toString())
            },
        )
    }

    private fun lockTicket(tx: Tx, id: String): Pair<String, String?> =
        tx.one("SELECT status, repair_id FROM support_tickets WHERE id = ? FOR UPDATE", id) { it.getString(1) to it.getString(2) } ?: throw notFound("Ticket not found.")

    fun assignTicket(p: AdminPrincipal, id: String, req: TicketAssignRequest): AdminTicketDetail {
        p.require(Permission.SUPPORT_MANAGE)
        return db.tx {
            lockTicket(this, id)
            val target = req.adminId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "adminId") }
            if (target != null && one("SELECT 1 FROM admin_users WHERE id = ? AND active AND role <> 'VIEWER'", target) { 1 } == null)
                throw badRequest("Assign to an active team member who can work tickets.")
            val before = one("SELECT assigned_to FROM support_tickets WHERE id = ?", id) { it.uuid(1)?.toString() }
            update("UPDATE support_tickets SET assigned_to = ?, updated_at = ? WHERE id = ?", target, s.now(), id)
            audit(this, p, "support.assign", "ticket", id, null, mapOf("assignedTo" to before), mapOf("assignedTo" to target?.toString()))
            ticketIn(this, id)
        }
    }

    /** A reply the customer sees (also on the linked repair's timeline), or an internal note only staff see. */
    fun addTicketMessage(p: AdminPrincipal, id: String, body: String, visibility: String): AdminTicketDetail {
        p.require(Permission.SUPPORT_MANAGE)
        val text = body.trim()
        if (text.isEmpty() || text.length > 2000) throw badRequest("Messages are 1 to 2,000 characters.")
        return db.tx {
            val (status, repair) = lockTicket(this, id)
            if (visibility == "CUSTOMER" && status == "CLOSED") throw conflict("ticket_closed", "Reopen the ticket to reply to the customer.")
            update("INSERT INTO ticket_messages (ticket_id, admin_id, visibility, body, created_at) VALUES (?,?,?,?,?)", id, p.id, visibility, text, s.now())
            update("UPDATE support_tickets SET updated_at = ? WHERE id = ?", s.now(), id)
            if (visibility == "CUSTOMER" && repair != null)
                s.event(this, repair, null, "INTERNAL", STAFF_ACTOR, title = "Support reply · $id", message = text.take(300), kind = "NOTE")
            audit(this, p, if (visibility == "CUSTOMER") "support.reply" else "support.note", "ticket", id, null, null, mapOf("visibility" to visibility, "body" to text))
            ticketIn(this, id)
        }
    }

    fun setTicketStatus(p: AdminPrincipal, id: String, req: TicketStatusRequest): AdminTicketDetail {
        p.require(Permission.SUPPORT_MANAGE)
        if (req.status !in setOf("OPEN", "CLOSED")) throw badRequest("status must be OPEN or CLOSED.")
        return db.tx {
            val (status, _) = lockTicket(this, id)
            if (status == req.status) throw conflict("no_change", "The ticket is already ${status.lowercase()}.")
            update("UPDATE support_tickets SET status = ?, closed_at = CASE WHEN ? = 'CLOSED' THEN ?::timestamptz ELSE NULL END, updated_at = ? WHERE id = ?",
                req.status, req.status, s.now(), s.now(), id)
            audit(this, p, if (req.status == "CLOSED") "support.close" else "support.reopen", "ticket", id, req.reason.trim().ifEmpty { null },
                mapOf("status" to status), mapOf("status" to req.status))
            ticketIn(this, id)
        }
    }

    // ==========================================================================================================
    // Team & access
    // ==========================================================================================================

    private fun userRow(rs: ResultSet) = AdminUserDto(rs.uuid("id").toString(), rs.getString("email"), rs.getString("name"), rs.getString("role"),
        rs.getBoolean("active"), rs.instant("created_at").toString(), rs.instant("last_login_at")?.toString())

    fun team(p: AdminPrincipal): List<AdminUserDto> {
        p.require(Permission.TEAM_MANAGE)
        return db.tx { query("SELECT * FROM admin_users ORDER BY active DESC, role, name", map = ::userRow) }
    }

    /** Names for assignment pickers; every role may read these. */
    fun staffOptions(): List<StaffOption> = db.tx {
        query("SELECT id, name, role FROM admin_users WHERE active AND role <> 'VIEWER' ORDER BY name") { StaffOption(it.uuid(1).toString(), it.getString(2), it.getString(3)) }
    }

    private val emailRegex = Regex("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[A-Za-z]{2,}$")

    private fun role(raw: String) = runCatching { AdminRole.valueOf(raw.uppercase()) }.getOrElse { throw badRequest("Role must be OWNER, OPERATIONS, SUPPORT, FINANCE or VIEWER.") }

    fun createUser(p: AdminPrincipal, req: AdminCreateRequest): AdminUserDto {
        p.require(Permission.TEAM_MANAGE)
        val email = req.email.trim()
        if (!emailRegex.matches(email)) throw badRequest("Enter a valid email address.")
        if (req.name.trim().length !in 2..60) throw badRequest("Name is 2 to 60 characters.")
        val role = role(req.role)
        Passwords.policyProblem(req.password)?.let { throw badRequest(it, "weak_password") }
        return try {
            db.tx {
                val id = UUID.randomUUID()
                update("INSERT INTO admin_users (id, email, name, role, password_hash, created_at) VALUES (?,?,?,?,?,?)",
                    id, email, req.name.trim(), role.name, Passwords.hash(req.password), s.now())
                audit(this, p, "team.create", "admin_user", id.toString(), null, null, mapOf("email" to email, "name" to req.name.trim(), "role" to role.name))
                one("SELECT * FROM admin_users WHERE id = ?", id, map = ::userRow)!!
            }
        } catch (e: Exception) {
            if (isUniqueViolation(e, "admin_users_email_key")) throw conflict("email_taken", "Someone on the team already uses that email.") else throw e
        }
    }

    fun updateUser(p: AdminPrincipal, id: String, req: AdminUpdateRequest): AdminUserDto {
        p.require(Permission.TEAM_MANAGE)
        val uid = parseUuid(id, "id")
        val newRole = req.role?.let(::role)
        return db.tx {
            // Serialise team changes so two owners can't demote each other at once.
            one("SELECT pg_advisory_xact_lock(4242)") { 1 }
            val cur = one("SELECT * FROM admin_users WHERE id = ? FOR UPDATE", uid, map = ::userRow) ?: throw notFound("Team member not found.")
            if (uid == p.id && (newRole != null && newRole.name != cur.role || req.active == false))
                throw conflict("self_change", "You can't change your own role or deactivate yourself. Ask another owner.")
            val losingOwner = cur.role == "OWNER" && cur.active && ((newRole != null && newRole != AdminRole.OWNER) || req.active == false)
            if (losingOwner && one("SELECT count(*) FROM admin_users WHERE role = 'OWNER' AND active AND id <> ?", uid) { it.getInt(1) } == 0)
                throw conflict("last_owner", "There must always be at least one active owner.")
            val name = req.name?.trim()?.also { if (it.length !in 2..60) throw badRequest("Name is 2 to 60 characters.") } ?: cur.name
            val active = req.active ?: cur.active
            val roleName = newRole?.name ?: cur.role
            update("UPDATE admin_users SET name = ?, role = ?, active = ? WHERE id = ?", name, roleName, active, uid)
            if (!active || roleName != cur.role) update("UPDATE admin_sessions SET revoked_at = ? WHERE admin_id = ? AND revoked_at IS NULL", s.now(), uid)
            audit(this, p, "team.update", "admin_user", uid.toString(), null,
                mapOf("name" to cur.name, "role" to cur.role, "active" to cur.active), mapOf("name" to name, "role" to roleName, "active" to active))
            one("SELECT * FROM admin_users WHERE id = ?", uid, map = ::userRow)!!
        }
    }

    /** An owner sets a new password for someone; all of that person's sessions end. */
    fun resetPassword(p: AdminPrincipal, id: String, req: PasswordResetRequest): AdminUserDto {
        p.require(Permission.TEAM_MANAGE)
        Passwords.policyProblem(req.password)?.let { throw badRequest(it, "weak_password") }
        val uid = parseUuid(id, "id")
        return db.tx {
            if (update("UPDATE admin_users SET password_hash = ? WHERE id = ?", Passwords.hash(req.password), uid) == 0) throw notFound("Team member not found.")
            update("UPDATE admin_sessions SET revoked_at = ? WHERE admin_id = ? AND revoked_at IS NULL AND token_hash <> ?", s.now(), uid, p.sessionHash)
            audit(this, p, "team.password_reset", "admin_user", uid.toString(), null, null, null)
            one("SELECT * FROM admin_users WHERE id = ?", uid, map = ::userRow)!!
        }
    }

    /** Anyone can change their own password with the current one. Other sessions end. */
    fun changeOwnPassword(p: AdminPrincipal, current: String, new: String) {
        Passwords.policyProblem(new)?.let { throw badRequest(it, "weak_password") }
        db.tx {
            val hash = one("SELECT password_hash FROM admin_users WHERE id = ? FOR UPDATE", p.id) { it.getString(1) }!!
            if (!Passwords.verify(current, hash)) throw ApiException(io.ktor.http.HttpStatusCode.Forbidden, "invalid_credentials", "Your current password is wrong.")
            update("UPDATE admin_users SET password_hash = ? WHERE id = ?", Passwords.hash(new), p.id)
            update("UPDATE admin_sessions SET revoked_at = ? WHERE admin_id = ? AND revoked_at IS NULL AND token_hash <> ?", s.now(), p.id, p.sessionHash)
            audit(this, p, "team.password_change", "admin_user", p.id.toString(), null, null, null)
        }
    }
}
