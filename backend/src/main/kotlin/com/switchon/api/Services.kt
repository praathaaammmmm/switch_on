package com.switchon.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KJson
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID

val IST: ZoneId = ZoneId.of("Asia/Kolkata")
const val WARRANTY_DAYS = 90L
const val SLOT_DAYS = 3
const val GSTIN = "29AAKCS4821M1Z6"

val json = KJson { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
private val random = SecureRandom()

fun parseUuid(s: String, what: String): UUID = runCatching { UUID.fromString(s) }.getOrElse { throw badRequest("$what must be a UUID.") }
fun parseDate(s: String): LocalDate = runCatching { LocalDate.parse(s) }.getOrElse { throw badRequest("Dates must look like 2026-10-22.") }

/** Everything the API does, over one database. [clock] is injectable so tests can move time. */
class Services(val db: Db, val config: Config, val clock: Clock = Clock.systemUTC()) {

    fun now(): Instant = clock.instant()
    fun todayIst(): LocalDate = LocalDate.now(clock.withZone(IST))
    internal fun hourIst(): Int = ZonedDateTime.now(clock.withZone(IST)).hour

    // =========================================================================================================
    // Customers and sessions
    // =========================================================================================================

    fun guestSession(installationId: String): GuestSessionResponse {
        val installation = parseUuid(installationId, "installationId")
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        var created = false
        val customerId = db.tx {
            val existing = one("SELECT customer_id FROM guest_installations WHERE installation_id = ?", installation) { it.uuid("customer_id") }
            val id = existing ?: UUID.randomUUID().also { id ->
                update("INSERT INTO customers (id, kind) VALUES (?, 'GUEST')", id)
                update("INSERT INTO guest_installations (installation_id, customer_id) VALUES (?, ?)", installation, id)
                created = true
            }
            update("INSERT INTO sessions (token_hash, customer_id, installation_id) VALUES (?, ?, ?)", sha256(token), id, installation)
            id!!
        }
        if (created && config.seedDemoForNewGuests) DemoSeeder(this).seed(customerId)
        return GuestSessionResponse(token, profile(customerId), created)
    }

    /** Resolves a bearer token to its customer, following a guest → registered merge if one happened. */
    fun authenticate(token: String?): UUID {
        if (token.isNullOrBlank()) throw unauthorized()
        return db.tx {
            var id = one("SELECT customer_id FROM sessions WHERE token_hash = ?", sha256(token)) { it.uuid("customer_id") } ?: throw unauthorized()
            repeat(5) { one("SELECT merged_into_id FROM customers WHERE id = ?", id) { it.uuid("merged_into_id") }?.let { m -> id = m } }
            id
        }
    }

    fun profile(customerId: UUID): ProfileDto = db.tx {
        one("SELECT * FROM customers WHERE id = ?", customerId) {
            ProfileDto(customerId.toString(), it.getString("kind"), it.getString("display_name"), it.getString("phone"), it.getString("email"))
        } ?: throw unauthorized()
    }

    internal fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // =========================================================================================================
    // Catalogue, serviceability and slots
    // =========================================================================================================

    fun devices(): DevicesResponse = db.tx {
        val models = query(
            "SELECT b.id, m.name FROM device_models m JOIN device_brands b ON b.id = m.brand_id ORDER BY m.sort_order"
        ) { it.getInt("id") to it.getString("name") }.groupBy({ it.first }, { it.second })
        val brands = query("SELECT * FROM device_brands ORDER BY sort_order") {
            it.getString("category_code") to BrandDto(it.getString("name"), it.getDouble("price_multiplier"), models[it.getInt("id")].orEmpty())
        }.groupBy({ it.first }, { it.second })
        DevicesResponse(query("SELECT * FROM device_categories WHERE active ORDER BY sort_order") {
            CategoryDto(it.getString("code"), it.getString("label"), brands[it.getString("code")].orEmpty())
        })
    }

    fun issues(): IssuesResponse = db.tx {
        val byIssue = query(
            """SELECT e.*, i.label AS issue_label FROM service_catalog_entries e JOIN repair_issues i ON i.code = e.issue_code
               JOIN device_categories c ON c.code = e.category_code WHERE e.active AND c.active ORDER BY c.sort_order"""
        ) {
            it.getString("issue_code") to OfferingDto(
                it.getString("category_code"), it.getInt("base_price_inr"), it.getString("turnaround"),
                it.getString("label_override") ?: it.getString("issue_label"),
            )
        }.groupBy({ it.first }, { it.second })
        IssuesResponse(
            query("SELECT * FROM repair_issues ORDER BY sort_order") {
                IssueDto(it.getString("code"), it.getString("label"), it.getString("hint"), byIssue[it.getString("code")].orEmpty())
            },
            query("SELECT pincode_prefix FROM service_areas WHERE active ORDER BY pincode_prefix") { it.getString(1) },
        )
    }

    fun estimate(req: EstimateRequest): EstimateResponse = db.tx { estimateIn(this, req.deviceCategory, req.issue, req.brand) }

    internal fun estimateIn(tx: Tx, category: String, issue: String, brand: String?): EstimateResponse = with(tx) {
        val entry = one(
            """SELECT e.base_price_inr, e.turnaround, coalesce(e.label_override, i.label) AS label
               FROM service_catalog_entries e JOIN repair_issues i ON i.code = e.issue_code
               JOIN device_categories c ON c.code = e.category_code
               WHERE e.category_code = ? AND e.issue_code = ? AND e.active AND c.active""", category, issue,
        ) { Triple(it.getInt(1), it.getString(2), it.getString(3)) }
            ?: throw unprocessable("not_offered", "We don't offer that repair for this device yet.")
        val multiplier = if (brand.isNullOrBlank()) null else
            one("SELECT price_multiplier FROM device_brands WHERE category_code = ? AND name = ?", category, brand) { it.getDouble(1) }
                ?: throw badRequest("Unknown brand for this device.")
        val (low, high) = Pricing.estimate(entry.first, multiplier)
        EstimateResponse(category, issue, brand?.takeIf { it.isNotBlank() }, entry.third, entry.first, low, high, entry.second, WARRANTY_DAYS.toInt())
    }

    fun serviceability(pin: String): ServiceabilityResponse = db.tx {
        ServiceabilityResponse(pin, serviceCity(this, pin) != null, serviceCity(this, pin))
    }

    internal fun serviceCity(tx: Tx, pin: String): String? =
        if (!Regex("^[1-9][0-9]{5}$").matches(pin)) null
        else tx.one("SELECT city FROM service_areas WHERE pincode_prefix = ? AND active", pin.take(3)) { it.getString(1) }

    fun slots(): SlotsResponse = db.tx { SlotsResponse(IST.id, slotsIn(this, excludeRepair = null)) }

    internal fun slotsIn(tx: Tx, excludeRepair: String?): List<SlotDto> = with(tx) {
        val today = todayIst()
        val nowHour = hourIst()
        val templates = query("SELECT start_hour, capacity FROM pickup_slot_templates ORDER BY start_hour") { it.getInt(1) to it.getInt(2) }
        val blocked = query("SELECT slot_date, start_hour FROM pickup_slot_blocks WHERE slot_date BETWEEN ? AND ?", today, today.plusDays(SLOT_DAYS - 1L)) {
            it.date("slot_date") to it.getInt("start_hour")
        }.toSet()
        val booked = query(
            """SELECT slot_date, slot_start_hour, count(*) FROM repairs
               WHERE slot_date BETWEEN ? AND ? AND status = ANY(?) AND id <> coalesce(?, '')
               GROUP BY slot_date, slot_start_hour""",
            today, today.plusDays(SLOT_DAYS - 1L), RepairStatus.entries.filter { it.beforePickup && it != RepairStatus.PICKUP_FAILED }.map { it.name }, excludeRepair,
        ) { (it.date("slot_date") to it.getInt(2)) to it.getInt(3) }.toMap()
        (0 until SLOT_DAYS).flatMap { offset ->
            val day = today.plusDays(offset.toLong())
            templates.filter { (h, _) -> offset > 0 || h > nowHour + 1 }.map { (h, cap) ->
                val remaining = if ((day to h) in blocked) 0 else (cap - (booked[day to h] ?: 0)).coerceAtLeast(0)
                SlotDto(day.toString(), h, h + 2, remaining > 0, remaining)
            }
        }
    }

    private fun requireSlot(tx: Tx, slot: SlotRef, excludeRepair: String? = null): Pair<LocalDate, Int> {
        val date = parseDate(slot.date)
        val match = slotsIn(tx, excludeRepair).firstOrNull { it.date == slot.date && it.startHour == slot.startHour }
            ?: throw unprocessable("slot_unavailable", "That pickup window isn't offered. Choose another.")
        if (!match.available) throw conflict("slot_full", "That pickup window just filled up. Choose another.")
        return date to slot.startHour
    }

    // =========================================================================================================
    // Addresses
    // =========================================================================================================

    fun addresses(customerId: UUID): List<AddressDto> = db.tx {
        query("SELECT * FROM addresses WHERE customer_id = ? AND deleted_at IS NULL ORDER BY is_default DESC, created_at", customerId, map = ::addressDto)
    }

    fun createAddress(customerId: UUID, req: AddressRequest): AddressDto = db.tx {
        val label = req.label.trim().ifEmpty { "Other" }.take(20)
        if (req.line1.isBlank() || req.area.isBlank() || req.city.isBlank()) throw badRequest("Flat or house, area and city are all needed.")
        if (!Regex("^[1-9][0-9]{5}$").matches(req.pincode)) throw badRequest("Pincodes have 6 digits and don't start with 0.")
        if (serviceCity(this, req.pincode) == null) throw unprocessable("not_serviceable", "We don't pick up from ${req.pincode} yet.")
        val first = one("SELECT count(*) FROM addresses WHERE customer_id = ? AND deleted_at IS NULL", customerId) { it.getInt(1) } == 0
        val id = UUID.randomUUID()
        update(
            "INSERT INTO addresses (id, customer_id, label, line1, line2, area, city, pincode, landmark, is_default) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, customerId, label, req.line1.trim(), req.line2.trim(), req.area.trim(), req.city.trim(), req.pincode, req.landmark.trim(), first,
        )
        one("SELECT * FROM addresses WHERE id = ?", id, map = ::addressDto)!!
    }

    fun deleteAddress(customerId: UUID, addressId: String) = db.tx {
        val id = parseUuid(addressId, "addressId")
        val wasDefault = one("SELECT is_default FROM addresses WHERE id = ? AND customer_id = ? AND deleted_at IS NULL", id, customerId) { it.getBoolean(1) }
            ?: throw notFound("Address not found.")
        update("UPDATE addresses SET deleted_at = ?, is_default = false WHERE id = ?", now(), id)
        if (wasDefault) update(
            """UPDATE addresses SET is_default = true WHERE id = (SELECT id FROM addresses WHERE customer_id = ? AND deleted_at IS NULL ORDER BY created_at LIMIT 1)""",
            customerId,
        )
    }

    fun makeDefaultAddress(customerId: UUID, addressId: String) = db.tx {
        val id = parseUuid(addressId, "addressId")
        if (update("UPDATE addresses SET is_default = (id = ?) WHERE customer_id = ? AND deleted_at IS NULL", id, customerId) == 0 ||
            one("SELECT 1 FROM addresses WHERE id = ? AND customer_id = ? AND deleted_at IS NULL", id, customerId) { 1 } == null
        ) throw notFound("Address not found.")
    }

    internal fun addressDto(rs: ResultSet) = AddressDto(
        rs.uuid("id").toString(), rs.getString("label"), rs.getString("line1"), rs.getString("line2"), rs.getString("area"),
        rs.getString("city"), rs.getString("pincode"), rs.getString("landmark"), rs.getBoolean("is_default"),
    )

    // =========================================================================================================
    // Repairs
    // =========================================================================================================

    internal class Row(rs: ResultSet) {
        val id: String = rs.getString("id")
        val customerId: UUID = rs.uuid("customer_id")!!
        val status = RepairStatus.valueOf(rs.getString("status"))
        val category: String = rs.getString("device_category")
        val issue: String = rs.getString("issue_code")
        val estimateLow = rs.getInt("estimate_low_inr")
        val estimateHigh = rs.getInt("estimate_high_inr")
        val deliveredAt = rs.instant("delivered_at")
        val warrantyClaimFor: String? = rs.getString("warranty_claim_for")
        val addressJson: String = rs.getString("address_snapshot")
        val contactName: String = rs.getString("contact_name")
    }

    /** Loads a repair only if it belongs to [customerId]; others' repairs are indistinguishable from missing ones. */
    internal fun load(tx: Tx, customerId: UUID, id: String, lock: Boolean = false): Row =
        tx.one("SELECT * FROM repairs WHERE id = ? AND customer_id = ?" + if (lock) " FOR UPDATE" else "", id, customerId) { Row(it) }
            ?: throw notFound("Repair not found.")

    fun createRepair(customerId: UUID, req: CreateRepairRequest): Pair<RepairDto, Boolean> {
        val requestId = parseUuid(req.clientRequestId, "clientRequestId")
        // Replay of a booking the app already sent (e.g. retried by WorkManager): hand back the same repair.
        db.tx { one("SELECT id FROM repairs WHERE customer_id = ? AND client_request_id = ?", customerId, requestId) { it.getString(1) } }
            ?.let { return repair(customerId, it) to false }

        validateContact(req.contactName, req.contactPhone)
        if (req.notes.length > 300) throw badRequest("Keep notes under 300 characters.")
        if (req.photoCount !in 0..4) throw badRequest("Attach up to 4 photos.")
        val symptoms = req.symptoms.map { it.trim() }.filter { it.isNotEmpty() }.take(10)

        return try {
            db.tx {
                val est = estimateIn(this, req.deviceCategory, req.issue, req.brand)
                if (!req.model.isNullOrBlank() && req.brand.isNullOrBlank()) throw badRequest("Choose the brand before the model.")
                if (!req.model.isNullOrBlank() && one(
                        "SELECT 1 FROM device_models m JOIN device_brands b ON b.id = m.brand_id WHERE b.category_code = ? AND b.name = ? AND m.name = ?",
                        req.deviceCategory, req.brand, req.model,
                    ) { 1 } == null
                ) throw badRequest("Unknown model for this brand.")
                val address = one(
                    "SELECT * FROM addresses WHERE id = ? AND customer_id = ? AND deleted_at IS NULL", parseUuid(req.addressId, "addressId"), customerId,
                    map = ::addressDto,
                ) ?: throw unprocessable("address_missing", "Choose a saved pickup address.")
                if (serviceCity(this, address.pincode) == null) throw unprocessable("not_serviceable", "We don't pick up from ${address.pincode} yet.")
                val (date, hour) = requireSlot(this, req.slot)
                val id = insertRepair(
                    this, customerId, requestId, req.deviceCategory, req.issue, req.brand?.takeIf { it.isNotBlank() }, req.model?.takeIf { it.isNotBlank() },
                    symptoms, req.notes.trim(), req.photoCount, est.low, est.high, est.turnaround, address, date, hour,
                    req.contactName.trim(), req.contactPhone, warrantyClaimFor = null,
                )
                event(this, id, RepairStatus.PICKUP_REQUESTED, "CUSTOMER", "You",
                    message = "Pickup ${slotLabel(date, hour)}. Estimate ₹${inr(est.low)} – ₹${inr(est.high)}.")
                transition(this, id, RepairStatus.PICKUP_REQUESTED, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On")
                rememberContact(this, customerId, req.contactName.trim(), req.contactPhone)
                id
            }.let { repair(customerId, it) to true }
        } catch (e: Exception) {
            // A concurrent replay won the race to insert: return what it created.
            if (isUniqueViolation(e, "repairs_customer_id_client_request_id_key"))
                repair(customerId, db.tx { one("SELECT id FROM repairs WHERE customer_id = ? AND client_request_id = ?", customerId, requestId) { it.getString(1) }!! }) to false
            else throw e
        }
    }

    internal fun insertRepair(
        tx: Tx, customerId: UUID, requestId: UUID, category: String, issue: String, brand: String?, model: String?, symptoms: List<String>,
        notes: String, photos: Int, low: Int, high: Int, turnaround: String, address: AddressDto, date: LocalDate, hour: Int,
        contactName: String, contactPhone: String, warrantyClaimFor: String?, status: RepairStatus = RepairStatus.PICKUP_REQUESTED,
        createdAt: Instant = now(),
    ): String {
        repeat(20) {
            val id = "SO-" + (100000 + random.nextInt(900000))
            if (tx.one("SELECT 1 FROM repairs WHERE id = ?", id) { 1 } != null) return@repeat
            tx.update(
                """INSERT INTO repairs (id, customer_id, client_request_id, status, device_category, issue_code, brand, model, symptoms, notes,
                   photo_count, estimate_low_inr, estimate_high_inr, turnaround, address_id, address_snapshot, slot_date, slot_start_hour,
                   contact_name, contact_phone, warranty_claim_for, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)""",
                id, customerId, requestId, status.name, category, issue, brand, model, symptoms, notes, photos, low, high, turnaround,
                UUID.fromString(address.id), Json(json.encodeToString(address)), date, hour, contactName, contactPhone, warrantyClaimFor, createdAt, createdAt,
            )
            return id
        }
        error("Couldn't allocate a repair id")
    }

    private fun rememberContact(tx: Tx, customerId: UUID, name: String, phone: String) {
        tx.update("UPDATE customers SET display_name = coalesce(display_name, ?), phone = coalesce(phone, ?) WHERE id = ?", name, phone, customerId)
    }

    private fun validateContact(name: String, phone: String) {
        if (name.trim().length !in 2..60) throw badRequest("Add a contact name of 2 to 60 characters.")
        if (!Regex("^[6-9][0-9]{9}$").matches(phone)) throw badRequest("Enter a 10-digit Indian mobile number starting with 6, 7, 8 or 9.")
    }

    fun repairs(customerId: UUID): List<RepairDto> = db.tx {
        query("SELECT id FROM repairs WHERE customer_id = ? ORDER BY created_at DESC", customerId) { it.getString(1) }.map { assemble(this, customerId, it) }
    }

    fun repair(customerId: UUID, id: String): RepairDto = db.tx { assemble(this, customerId, id) }

    fun cancel(customerId: UUID, id: String, reason: String): RepairDto {
        db.tx {
            val r = load(this, customerId, id, lock = true)
            if (r.status == RepairStatus.CANCELLED) throw conflict("already_cancelled", "This repair is already cancelled.")
            if (!r.status.beforePickup) throw conflict("cancel_not_allowed", "The device has already been picked up, so this can't be cancelled here. Chat with us instead.")
            val why = reason.trim().ifEmpty { "No reason given" }.take(120)
            update("UPDATE repairs SET cancelled_at = ?, cancel_reason = ? WHERE id = ?", now(), why, id)
            transition(this, id, r.status, RepairStatus.CANCELLED, "CUSTOMER", "You", message = "$why · no charge")
        }
        return repair(customerId, id)
    }

    fun reschedule(customerId: UUID, id: String, slot: SlotRef): RepairDto {
        db.tx {
            val r = load(this, customerId, id, lock = true)
            if (!r.status.beforePickup || r.status == RepairStatus.CANCELLED || r.status == RepairStatus.DRAFT)
                throw conflict("reschedule_not_allowed", "The partner has already set off, so the window can't change now.")
            val (date, hour) = requireSlot(this, slot, excludeRepair = id)
            update("UPDATE repairs SET slot_date = ?, slot_start_hour = ?, updated_at = ? WHERE id = ?", date, hour, now(), id)
            if (r.status == RepairStatus.PICKUP_FAILED) transition(this, id, r.status, RepairStatus.PICKUP_SCHEDULED, "CUSTOMER", "You", message = "New window ${slotLabel(date, hour)}")
            else event(this, id, null, "CUSTOMER", "You", title = "Pickup rescheduled", message = slotLabel(date, hour))
        }
        return repair(customerId, id)
    }

    fun decideQuote(customerId: UUID, id: String, req: QuoteDecisionRequest): RepairDto {
        val approve = when (req.decision.uppercase()) {
            "APPROVE", "APPROVED" -> true
            "DECLINE", "DECLINED" -> false
            else -> throw badRequest("decision must be APPROVE or DECLINE.")
        }
        db.tx {
            val r = load(this, customerId, id, lock = true)
            val quote = one("SELECT id, decision, total_inr FROM quotes WHERE repair_id = ? FOR UPDATE", id) {
                Triple(it.uuid("id")!!, it.getString("decision"), it.getInt("total_inr"))
            } ?: throw conflict("no_quote", "There's no quote on this repair yet.")
            if (quote.second != null || r.status != RepairStatus.QUOTE_PENDING) throw conflict("quote_already_decided", "This quote has already been decided.")
            val reason = req.reason?.trim()?.take(120)
            update(
                "UPDATE quotes SET decision = ?, decided_at = ?, decline_reason = ? WHERE id = ?",
                if (approve) "APPROVED" else "DECLINED", now(), if (approve) null else (reason ?: "No reason given"), quote.first,
            )
            if (approve) {
                transition(this, id, r.status, RepairStatus.REPAIRING, "CUSTOMER", "You", title = "Quote approved",
                    message = "₹${inr(quote.third)} approved. The repair has started.", metadata = mapOf("total" to quote.third.toString()))
            } else {
                transition(this, id, r.status, RepairStatus.QUOTE_DECLINED, "CUSTOMER", "You", message = reason ?: "No reason given")
                val seal = sealCode()
                val shop = one("SELECT s.name FROM repair_shops s JOIN repairs r ON r.shop_id = s.id WHERE r.id = ?", id) { it.getString(1) } ?: "Repair shop"
                update("UPDATE repairs SET seal_code = ? WHERE id = ?", seal, id)
                handover(this, id, "RETURN", seal, shop, 1)
                transition(this, id, RepairStatus.QUOTE_DECLINED, RepairStatus.RETURN_SCHEDULED, "SHOP", shop,
                    message = "Resealed unrepaired with seal $seal. Nothing to pay.", kind = "CUSTODY")
            }
        }
        return repair(customerId, id)
    }

    fun pay(customerId: UUID, id: String, req: PaymentRequest): RepairDto {
        val key = parseUuid(req.idempotencyKey, "idempotencyKey")
        val method = req.method.uppercase().takeIf { it in setOf("UPI", "CARD", "CASH") } ?: throw badRequest("method must be UPI, CARD or CASH.")
        try {
            db.tx {
                val r = load(this, customerId, id, lock = true)
                // Same key again: the payment already went through, so this is a harmless replay.
                val existing = one("SELECT idempotency_key FROM payment_records WHERE repair_id = ?", id) { it.uuid(1) }
                if (existing != null) {
                    if (existing == key) return@tx
                    throw conflict("payment_already_recorded", "This repair is already paid.")
                }
                if (r.status != RepairStatus.PAYMENT_PENDING) throw conflict("payment_not_due", "Nothing is due on this repair right now.")
                when (method) {
                    "UPI" -> if (req.upiId == null || !Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,63}$").matches(req.upiId.trim()))
                        throw badRequest("That doesn't look like a UPI ID. It should be like name@bank.")
                    "CASH" -> if (!req.cashConfirmed) throw badRequest("Confirm you've handed over the cash.")
                }
                // The amount is the approved quote total, never anything the app sends.
                val amount = amountDue(this, r)
                if (amount <= 0) throw conflict("payment_not_due", "Nothing is due on this repair.")
                val partner = one("SELECT p.name FROM pickup_partners p JOIN repairs r ON r.partner_id = p.id WHERE r.id = ?", id) { it.getString(1) } ?: "partner"
                val ref = when (method) {
                    "UPI" -> "UPI ${req.upiId!!.trim()} · ref " + (400000000000L + (random.nextDouble() * 99999999999L).toLong())
                    "CARD" -> "Card · auth " + (100000 + random.nextInt(900000))
                    else -> "Cash to $partner"
                }
                update("INSERT INTO payment_records (id, repair_id, method, amount_inr, reference, idempotency_key, recorded_at) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID(), id, method, amount, ref, key, now())
                transition(this, id, r.status, RepairStatus.PAYMENT_COMPLETED, "CUSTOMER", "You", title = "Paid by ${method.lowercase().replaceFirstChar { it.uppercase() }.let { if (it == "Upi") "UPI" else it }}",
                    message = "₹${inr(amount)} · $ref", kind = "PAYMENT", metadata = mapOf("amount" to amount.toString(), "method" to method))
                issueInvoice(this, id, amount)
                val start = (r.deliveredAt ?: now()).atZone(IST).toLocalDate()
                val coverage = one("SELECT warranty_text FROM quotes WHERE repair_id = ?", id) { it.getString(1) } ?: "90 days on parts and labour"
                update("INSERT INTO warranties (repair_id, starts_on, ends_on, coverage) VALUES (?,?,?,?)", id, start, start.plusDays(WARRANTY_DAYS), coverage)
                transition(this, id, RepairStatus.PAYMENT_COMPLETED, RepairStatus.WARRANTY_ACTIVE, "SYSTEM", "Switch On",
                    message = "Covered until ${start.plusDays(WARRANTY_DAYS)}.")
            }
        } catch (e: Exception) {
            // Two taps raced past the check: the database's unique constraint let only one through.
            if (isUniqueViolation(e)) {
                val sameKey = db.tx { one("SELECT idempotency_key FROM payment_records WHERE repair_id = ?", id) { it.uuid(1) } } == key
                if (!sameKey) throw conflict("payment_already_recorded", "This repair is already paid.")
            } else throw e
        }
        return repair(customerId, id)
    }

    internal fun issueInvoice(tx: Tx, id: String, total: Int) {
        val (taxable, cgst, sgst) = Pricing.gstSplit(total)
        val no = tx.one("SELECT nextval('invoice_number_seq')") { it.getLong(1) }!!
        tx.update("INSERT INTO invoices (id, repair_id, invoice_no, taxable_inr, cgst_inr, sgst_inr, total_inr, issued_at) VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), id, "INV/2026-27/%06d".format(no), taxable, cgst, sgst, total, now())
    }

    fun invoice(customerId: UUID, id: String): InvoiceDto = db.tx {
        val r = load(this, customerId, id)
        val dto = assemble(this, customerId, id)
        val payment = dto.payment ?: throw notFound("There's no invoice for this repair yet.")
        one("SELECT * FROM invoices WHERE repair_id = ?", id) {
            val a = dto.address
            InvoiceDto(
                invoiceNo = it.getString("invoice_no"), repairId = id, issuedAt = it.instant("issued_at").toString(),
                billedTo = r.contactName, address = listOf(a.line1, a.line2, a.area, "${a.city} ${a.pincode}").filter(String::isNotBlank).joinToString(", "),
                items = dto.quote?.items.orEmpty(), taxable = it.getInt("taxable_inr"), cgst = it.getInt("cgst_inr"), sgst = it.getInt("sgst_inr"),
                total = it.getInt("total_inr"), payment = payment, warrantyEndsOn = dto.warranty?.endsOn, gstin = GSTIN,
            )
        } ?: throw notFound("There's no invoice for this repair yet.")
    }

    internal fun amountDue(tx: Tx, r: Row): Int {
        if (r.warrantyClaimFor != null) return 0
        val q = tx.one("SELECT decision, total_inr FROM quotes WHERE repair_id = ?", r.id) { it.getString(1) to it.getInt(2) } ?: return 0
        return if (q.first == "APPROVED") q.second else 0
    }

    // ---- Events and transitions -----------------------------------------------------------------------------

    /** Moves a repair between statuses after checking the transition table, and records the event. */
    internal fun transition(
        tx: Tx, id: String, from: RepairStatus, to: RepairStatus, actorType: String, actorName: String,
        title: String? = null, message: String? = null, kind: String = "STATUS", metadata: Map<String, String> = emptyMap(), at: Instant = now(),
    ) {
        if (!canTransition(from, to)) throw conflict("invalid_transition", "This repair can't move from ${from.name} to ${to.name}.")
        val n = tx.update("UPDATE repairs SET status = ?, updated_at = ? WHERE id = ? AND status = ?", to.name, at, id, from.name)
        if (n != 1) throw conflict("stale_state", "This repair changed in the meantime. Refresh and try again.")
        event(tx, id, to, actorType, actorName, title, message, kind, metadata, at)
    }

    internal fun event(
        tx: Tx, id: String, status: RepairStatus?, actorType: String, actorName: String,
        title: String? = null, message: String? = null, kind: String = "STATUS", metadata: Map<String, String> = emptyMap(), at: Instant = now(),
    ) {
        tx.update(
            "INSERT INTO repair_status_events (repair_id, status, kind, title, customer_message, actor_type, actor_name, metadata, occurred_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
            id, status?.name, kind, title ?: status?.title ?: "Update", message ?: status?.message ?: "", actorType, actorName,
            Json(json.encodeToString(metadata)), at,
        )
    }

    internal fun handover(tx: Tx, id: String, stage: String, seal: String, by: String, photos: Int, at: Instant = now()) {
        tx.update("INSERT INTO handover_records (id, repair_id, stage, seal_code, handled_by, photo_count, occurred_at) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(), id, stage, seal, by, photos, at)
    }

    internal fun sealCode(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { a[random.nextInt(a.length)] }.joinToString("") + "-" + (1000 + random.nextInt(9000))
    }

    // ---- Assembly -------------------------------------------------------------------------------------------

    internal fun assemble(tx: Tx, customerId: UUID, id: String): RepairDto = with(tx) {
        one("SELECT * FROM repairs WHERE id = ? AND customer_id = ?", id, customerId) { rs ->
            val status = RepairStatus.valueOf(rs.getString("status"))
            val partner = rs.uuid("partner_id")?.let { pid ->
                one("SELECT * FROM pickup_partners WHERE id = ?", pid) {
                    PartnerDto(it.getString("name"), it.getString("phone"), it.getString("vehicle"), it.getDouble("rating"), it.getInt("trips"))
                }
            }
            val shop = rs.uuid("shop_id")?.let { sid ->
                one("SELECT * FROM repair_shops WHERE id = ?", sid) {
                    ShopDto(it.getString("name"), it.getString("area"), it.getDouble("rating"), it.getInt("reviews"), it.getString("technician"), it.getString("verified_since"))
                }
            }
            val quote = one("SELECT * FROM quotes WHERE repair_id = ?", id) { q ->
                val qid = q.uuid("id")
                val diagnosis = one("SELECT * FROM diagnoses WHERE repair_id = ?", id) { it.getString("summary") to it.strings("findings") }
                QuoteDto(
                    diagnosis?.first ?: "", diagnosis?.second.orEmpty(),
                    query("SELECT * FROM quote_line_items WHERE quote_id = ? ORDER BY sort_order", qid) {
                        LineItemDto(it.getString("label"), it.getString("detail"), it.getString("kind"), it.getInt("amount_inr"))
                    },
                    q.getInt("total_inr"), q.getString("warranty_text"), q.instant("sent_at").toString(), q.getString("decision"),
                    q.instant("decided_at")?.toString(), q.getString("decline_reason"),
                )
            }
            val events = query("SELECT * FROM repair_status_events WHERE repair_id = ? ORDER BY occurred_at, id", id) {
                EventDto(
                    it.instant("occurred_at").toString(), it.getString("status"), it.getString("kind"), it.getString("title"),
                    it.getString("customer_message"), it.getString("actor_type"), it.getString("actor_name"),
                    json.decodeFromString<Map<String, String>>(it.getString("metadata")),
                )
            }
            val handovers = query("SELECT * FROM handover_records WHERE repair_id = ? ORDER BY occurred_at", id) {
                HandoverDto(it.getString("stage"), it.getString("seal_code"), it.getString("handled_by"), it.getInt("photo_count"), it.getBoolean("seal_intact"), it.instant("occurred_at").toString())
            }
            val payment = one("SELECT * FROM payment_records WHERE repair_id = ?", id) {
                PaymentDto(it.getString("method"), it.getInt("amount_inr"), it.getString("reference"), it.instant("recorded_at").toString())
            }
            val warranty = one("SELECT * FROM warranties WHERE repair_id = ?", id) {
                WarrantyDto(it.date("starts_on").toString(), it.date("ends_on").toString(), it.getString("coverage"))
            }
            val row = Row(rs)
            val due = if (payment == null && status == RepairStatus.PAYMENT_PENDING) amountDue(this, row) else 0
            RepairDto(
                id = id, status = status.name, deviceCategory = row.category, issue = row.issue,
                brand = rs.getString("brand"), model = rs.getString("model"), symptoms = rs.strings("symptoms"), notes = rs.getString("notes"),
                photoCount = rs.getInt("photo_count"), estimateLow = row.estimateLow, estimateHigh = row.estimateHigh, turnaround = rs.getString("turnaround"),
                address = json.decodeFromString(row.addressJson), slot = SlotRef(rs.date("slot_date").toString(), rs.getInt("slot_start_hour")),
                contactName = row.contactName, contactPhone = rs.getString("contact_phone"),
                createdAt = rs.instant("created_at").toString(), updatedAt = rs.instant("updated_at").toString(),
                partner = partner, shop = shop, sealCode = rs.getString("seal_code"), quote = quote, events = events, handovers = handovers,
                payment = payment, invoiceNo = one("SELECT invoice_no FROM invoices WHERE repair_id = ?", id) { it.getString(1) },
                warranty = warranty, deliveredAt = row.deliveredAt?.toString(), cancelledAt = rs.instant("cancelled_at")?.toString(),
                cancelReason = rs.getString("cancel_reason"), warrantyClaimFor = row.warrantyClaimFor,
                amountDue = due, canCancel = status.beforePickup && status != RepairStatus.CANCELLED && status != RepairStatus.DRAFT,
                canReschedule = status.beforePickup && status != RepairStatus.CANCELLED && status != RepairStatus.DRAFT,
            )
        } ?: throw notFound("Repair not found.")
    }

    // =========================================================================================================
    // Warranty claims and support
    // =========================================================================================================

    /** Why [repairId] can't take a warranty claim today, or null when it can. */
    fun claimBlocker(tx: Tx, customerId: UUID, repairId: String): String? {
        val r = load(tx, customerId, repairId)
        if (r.warrantyClaimFor != null) return "This is already a warranty job."
        if (r.status != RepairStatus.WARRANTY_ACTIVE) return "Only paid, delivered repairs are covered."
        val ends = tx.one("SELECT ends_on FROM warranties WHERE repair_id = ?", repairId) { it.date(1) } ?: return "This repair has no warranty."
        if (todayIst().isAfter(ends)) return "Cover ended on $ends."
        if (tx.one("SELECT 1 FROM warranty_claims WHERE original_repair_id = ? AND status IN ('OPEN', 'INFO_REQUESTED', 'APPROVED')", repairId) { 1 } != null)
            return "A claim for this repair is already open."
        return null
    }

    fun createClaim(customerId: UUID, req: WarrantyClaimRequest): Pair<WarrantyClaimCreated, Boolean> {
        val requestId = parseUuid(req.clientRequestId, "clientRequestId")
        db.tx { one("SELECT id FROM repairs WHERE customer_id = ? AND client_request_id = ?", customerId, requestId) { it.getString(1) } }?.let { existing ->
            return WarrantyClaimCreated(claims(customerId).first { it.claimRepairId == existing }, repair(customerId, existing)) to false
        }
        if (req.problem.isBlank() || req.problem.length > 80) throw badRequest("Say what's wrong in a few words.")
        if (req.detail.length > 300) throw badRequest("Keep the description under 300 characters.")
        val claimRepairId = try {
            db.tx {
                val o = load(this, customerId, req.originalRepairId, lock = true)
                claimBlocker(this, customerId, o.id)?.let { throw unprocessable("not_eligible", it) }
                val address = one(
                    "SELECT * FROM addresses WHERE id = ? AND customer_id = ? AND deleted_at IS NULL", parseUuid(req.addressId, "addressId"), customerId, map = ::addressDto,
                ) ?: throw unprocessable("address_missing", "Choose a saved pickup address.")
                val (date, hour) = requireSlot(this, req.slot)
                val orig = one("SELECT * FROM repairs WHERE id = ?", o.id) { it.getString("brand") to it.getString("model") }!!
                val contact = one("SELECT contact_name, contact_phone FROM repairs WHERE id = ?", o.id) { it.getString(1) to it.getString(2) }!!
                val turnaround = one("SELECT turnaround FROM repairs WHERE id = ?", o.id) { it.getString(1) }!!
                val id = insertRepair(this, customerId, requestId, o.category, o.issue, orig.first, orig.second, listOf(req.problem.trim()), req.detail.trim(),
                    0, 0, 0, turnaround, address, date, hour, contact.first, contact.second, warrantyClaimFor = o.id)
                update("INSERT INTO warranty_claims (id, customer_id, original_repair_id, claim_repair_id, problem, detail, status, opened_at) VALUES (?,?,?,?,?,?,'OPEN',?)",
                    UUID.randomUUID(), customerId, o.id, id, req.problem.trim(), req.detail.trim(), now())
                event(this, id, RepairStatus.PICKUP_REQUESTED, "CUSTOMER", "You", title = "Warranty claim raised", message = "For ${o.id} · ${req.problem.trim()} · free pickup")
                transition(this, id, RepairStatus.PICKUP_REQUESTED, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On")
                event(this, o.id, null, "CUSTOMER", "You", title = "Warranty claim opened", message = "New job $id", kind = "NOTE")
                id
            }
        } catch (e: Exception) {
            if (isUniqueViolation(e, "warranty_claims_one_open")) throw unprocessable("not_eligible", "A claim for this repair is already open.")
            throw e
        }
        return WarrantyClaimCreated(claims(customerId).first { it.claimRepairId == claimRepairId }, repair(customerId, claimRepairId)) to true
    }

    fun claims(customerId: UUID): List<WarrantyClaimDto> = db.tx {
        query("SELECT * FROM warranty_claims WHERE customer_id = ? ORDER BY opened_at DESC", customerId) {
            WarrantyClaimDto(it.uuid("id").toString(), it.getString("original_repair_id"), it.getString("claim_repair_id"), it.getString("problem"),
                it.getString("detail"), it.getString("status"), it.instant("opened_at").toString(), it.instant("resolved_at")?.toString())
        }
    }

    fun createTicket(customerId: UUID, req: SupportTicketRequest): Pair<SupportTicketDto, Boolean> {
        val requestId = parseUuid(req.clientRequestId, "clientRequestId")
        val detail = req.detail.trim()
        if (detail.length !in 10..500) throw badRequest("Describe the issue in 10 to 500 characters.")
        if (req.topic.isBlank() || req.topic.length > 80) throw badRequest("Choose a topic.")
        return db.tx {
            ticketBy(this, customerId, requestId)?.let { return@tx it to false }
            if (req.repairId != null) load(this, customerId, req.repairId)
            var id: String
            do id = "TKT-" + (10000 + random.nextInt(90000)) while (one("SELECT 1 FROM support_tickets WHERE id = ?", id) { 1 } != null)
            update("INSERT INTO support_tickets (id, customer_id, client_request_id, repair_id, topic, detail, created_at) VALUES (?,?,?,?,?,?,?)",
                id, customerId, requestId, req.repairId, req.topic.trim(), detail, now())
            if (req.repairId != null) event(this, req.repairId, null, "CUSTOMER", "You", title = "Support ticket $id", message = req.topic.trim(), kind = "NOTE")
            ticketBy(this, customerId, requestId)!! to true
        }
    }

    private fun ticketBy(tx: Tx, customerId: UUID, requestId: UUID) =
        tx.one("SELECT * FROM support_tickets WHERE customer_id = ? AND client_request_id = ?", customerId, requestId, map = ::ticketDto)?.let { withReplies(tx, it) }

    fun tickets(customerId: UUID): List<SupportTicketDto> = db.tx {
        query("SELECT * FROM support_tickets WHERE customer_id = ? ORDER BY created_at DESC", customerId, map = ::ticketDto).map { withReplies(this, it) }
    }

    internal fun ticketDto(rs: ResultSet) = SupportTicketDto(
        rs.getString("id"), rs.getString("repair_id"), rs.getString("topic"), rs.getString("detail"), rs.getString("status"), rs.instant("created_at").toString(),
    )

    /** Adds the staff replies the customer may see. Internal notes never leave the admin API. */
    private fun withReplies(tx: Tx, t: SupportTicketDto) = t.copy(replies = tx.query(
        "SELECT body, created_at FROM ticket_messages WHERE ticket_id = ? AND visibility = 'CUSTOMER' ORDER BY id", t.id,
    ) { TicketReplyDto(it.getString(1), it.instant("created_at").toString()) })

    // =========================================================================================================
    // Development-only operations (internal routes, demo seed)
    // =========================================================================================================

    internal fun loadAny(tx: Tx, id: String, lock: Boolean = true): Row =
        tx.one("SELECT * FROM repairs WHERE id = ?" + if (lock) " FOR UPDATE" else "", id) { Row(it) } ?: throw notFound("Repair not found.")

    /** Moves a repair one step along the happy path, doing what the partner and shop would. */
    fun advance(id: String): RepairDto {
        val customer = db.tx {
            val r = loadAny(this, id)
            val shopName = { one("SELECT s.name FROM repair_shops s JOIN repairs r ON r.shop_id = s.id WHERE r.id = ?", id) { it.getString(1) } ?: "Repair shop" }
            val partnerName = { one("SELECT p.name FROM pickup_partners p JOIN repairs r ON r.partner_id = p.id WHERE r.id = ?", id) { it.getString(1) } ?: "Pickup partner" }
            when (r.status) {
                RepairStatus.PICKUP_REQUESTED -> transition(this, id, r.status, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On")
                RepairStatus.PICKUP_SCHEDULED -> {
                    val p = one("SELECT id, name, vehicle FROM pickup_partners ORDER BY random() LIMIT 1") { Triple(it.uuid(1)!!, it.getString(2), it.getString(3)) }!!
                    update("UPDATE repairs SET partner_id = ? WHERE id = ?", p.first, id)
                    transition(this, id, r.status, RepairStatus.PARTNER_ASSIGNED, "SYSTEM", "Switch On", message = "${p.second} · ${p.third}")
                }
                RepairStatus.PARTNER_ASSIGNED -> {
                    val seal = sealCode()
                    update("UPDATE repairs SET seal_code = ? WHERE id = ?", seal, id)
                    event(this, id, null, "PARTNER", partnerName(), title = "Identity checked at the door", message = "OTP matched", kind = "CUSTODY")
                    handover(this, id, "PICKUP", seal, partnerName(), 3)
                    transition(this, id, r.status, RepairStatus.PICKED_UP, "PARTNER", partnerName(), title = "Device sealed and picked up",
                        message = "Seal $seal · 3 handover photos", kind = "CUSTODY", metadata = mapOf("seal" to seal))
                }
                RepairStatus.PICKED_UP -> {
                    val s = one("SELECT id, name FROM repair_shops ORDER BY random() LIMIT 1") { it.uuid(1)!! to it.getString(2) }!!
                    update("UPDATE repairs SET shop_id = ? WHERE id = ?", s.first, id)
                    val seal = one("SELECT seal_code FROM repairs WHERE id = ?", id) { it.getString(1) }!!
                    handover(this, id, "SHOP_INTAKE", seal, s.second, 1)
                    transition(this, id, r.status, RepairStatus.AT_SHOP, "SHOP", s.second, title = "Handed to repair shop",
                        message = "Seal intact on arrival · ${s.second}", kind = "CUSTODY")
                }
                RepairStatus.AT_SHOP -> diagnose(this, r, shopName())
                RepairStatus.DIAGNOSED -> sendQuote(this, r, null, shopName())
                RepairStatus.REPAIRING -> transition(this, id, r.status, RepairStatus.QUALITY_CHECK, "SHOP", shopName(), title = "Repair finished", message = "Moved to quality check")
                RepairStatus.QUALITY_CHECK, RepairStatus.QUOTE_DECLINED, RepairStatus.REPAIR_NOT_POSSIBLE -> {
                    val seal = sealCode()
                    update("UPDATE repairs SET seal_code = ? WHERE id = ?", seal, id)
                    handover(this, id, "RETURN", seal, shopName(), 1)
                    transition(this, id, r.status, RepairStatus.RETURN_SCHEDULED, "SHOP", shopName(), title = "Out for delivery",
                        message = "Resealed with seal $seal", kind = "CUSTODY")
                }
                RepairStatus.RETURN_SCHEDULED -> deliver(this, r, partnerName())
                else -> throw conflict("cannot_advance", "Nothing to advance from ${r.status.name}" +
                    if (r.status == RepairStatus.QUOTE_PENDING) " (waiting for the customer's decision)." else ".")
            }
            r.customerId
        }
        return repair(customer, id)
    }

    internal fun diagnose(tx: Tx, r: Row, shop: String, summary: String? = null, findings: List<String>? = null) {
        val (_, d) = Pricing.mockQuote(r.issue, r.issue, r.estimateLow, r.estimateHigh)
        tx.update("INSERT INTO diagnoses (repair_id, summary, findings, diagnosed_at) VALUES (?,?,?,?) ON CONFLICT (repair_id) DO NOTHING",
            r.id, summary ?: d.summary, findings ?: d.findings, now())
        transition(tx, r.id, r.status, RepairStatus.DIAGNOSED, "SHOP", shop, message = summary ?: d.summary)
    }

    internal fun sendQuote(tx: Tx, r: Row, items: List<LineItemDto>?, shop: String) = with(tx) {
        val label = one("SELECT coalesce(e.label_override, i.label) FROM service_catalog_entries e JOIN repair_issues i ON i.code = e.issue_code WHERE e.category_code = ? AND e.issue_code = ?",
            r.category, r.issue) { it.getString(1) } ?: r.issue
        val lines = items ?: if (r.warrantyClaimFor != null) listOf(
            LineItemDto("Replacement part (warranty)", "Part", "PART", 0),
            LineItemDto("Labour (warranty)", "Covered", "LABOUR", 0),
            LineItemDto("Pickup and drop", "Doorstep, both ways", "FEE", 0),
        ) else Pricing.mockQuote(r.issue, label, r.estimateLow, r.estimateHigh).first.map { LineItemDto(it.label, it.detail, it.kind, it.amount) }
        if (lines.any { it.amount < 0 || it.kind !in setOf("PART", "LABOUR", "FEE") }) throw badRequest("Line items need a kind and a non-negative amount.")
        val total = lines.sumOf { it.amount }
        val qid = UUID.randomUUID()
        update("INSERT INTO quotes (id, repair_id, warranty_text, total_inr, sent_at) VALUES (?,?,?,?,?)", qid, r.id,
            if (r.warrantyClaimFor != null) "90 days from this repair" else "90 days on parts and labour", total, now())
        lines.forEachIndexed { i, l ->
            update("INSERT INTO quote_line_items (id, quote_id, label, detail, kind, amount_inr, sort_order) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), qid, l.label, l.detail, l.kind, l.amount, i)
        }
        val verdict = when {
            r.warrantyClaimFor != null -> "covered by warranty"
            total < r.estimateLow -> "below your estimate"
            total <= r.estimateHigh -> "within your estimate"
            else -> "₹${inr(total - r.estimateHigh)} above your estimate"
        }
        transition(this, r.id, r.status, RepairStatus.QUOTE_PENDING, "SHOP", shop, message = "₹${inr(total)} · $verdict", kind = "ALERT",
            metadata = mapOf("total" to total.toString()))
    }

    internal fun deliver(tx: Tx, r: Row, partner: String) = with(tx) {
        update("UPDATE repairs SET delivered_at = ? WHERE id = ?", now(), r.id)
        val seal = one("SELECT seal_code FROM repairs WHERE id = ?", r.id) { it.getString(1) } ?: ""
        handover(this, r.id, "DELIVERY", seal, partner, 0)
        val unrepaired = one("SELECT 1 FROM repair_status_events WHERE repair_id = ? AND status IN ('QUOTE_DECLINED', 'REPAIR_NOT_POSSIBLE')", r.id) { 1 } != null
        transition(this, r.id, r.status, RepairStatus.DELIVERED, "PARTNER", partner, title = if (unrepaired) "Returned to you" else "Delivered",
            message = "Seal checked and opened with you", kind = "CUSTODY")
        if (r.warrantyClaimFor != null) {
            update("UPDATE warranty_claims SET status = 'RESOLVED', resolved_at = ? WHERE claim_repair_id = ?", now(), r.id)
            event(this, r.warrantyClaimFor, null, "SYSTEM", "Switch On", title = "Warranty claim resolved", message = "Job ${r.id} delivered", kind = "NOTE")
        } else if (!unrepaired && amountDue(this, r) > 0) {
            transition(this, r.id, RepairStatus.DELIVERED, RepairStatus.PAYMENT_PENDING, "SYSTEM", "Switch On")
        }
    }

    fun internalQuote(id: String, req: InternalQuoteRequest): RepairDto {
        val customer = db.tx {
            var r = loadAny(this, id)
            val shop = one("SELECT s.name FROM repair_shops s JOIN repairs r ON r.shop_id = s.id WHERE r.id = ?", id) { it.getString(1) } ?: "Repair shop"
            if (r.status == RepairStatus.AT_SHOP) { diagnose(this, r, shop, req.diagnosis, req.findings); r = loadAny(this, id) }
            if (r.status != RepairStatus.DIAGNOSED) throw conflict("invalid_transition", "Quotes are sent after diagnosis (status is ${r.status.name}).")
            sendQuote(this, r, req.items, shop)
            r.customerId
        }
        return repair(customer, id)
    }

    fun pickupFailed(id: String, reason: String): RepairDto {
        val customer = db.tx {
            val r = loadAny(this, id)
            transition(this, id, r.status, RepairStatus.PICKUP_FAILED, "PARTNER", "Pickup partner", message = reason.ifBlank { RepairStatus.PICKUP_FAILED.message }, kind = "ALERT")
            r.customerId
        }
        return repair(customer, id)
    }

    fun notPossible(id: String, reason: String): RepairDto {
        val customer = db.tx {
            val r = loadAny(this, id)
            transition(this, id, r.status, RepairStatus.REPAIR_NOT_POSSIBLE, "SHOP", "Repair shop", message = reason.ifBlank { RepairStatus.REPAIR_NOT_POSSIBLE.message }, kind = "ALERT")
            r.customerId
        }
        return repair(customer, id)
    }

    // ---- Formatting helpers ----

    fun slotLabel(date: LocalDate, hour: Int) = "$date ${hour12(hour)} – ${hour12(hour + 2)} IST"
    internal fun hour12(h: Int) = "${((h + 11) % 12) + 1} ${if (h % 24 < 12) "AM" else "PM"}"
    fun inr(n: Int): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN")).format(n)

    // Seeder needs these.
    internal fun seedInsert(
        tx: Tx, customerId: UUID, category: String, issue: String, brand: String?, model: String?, symptoms: List<String>, notes: String, photos: Int,
        low: Int, high: Int, turnaround: String, address: AddressDto, date: LocalDate, hour: Int, contactName: String, contactPhone: String,
        status: RepairStatus, createdAt: Instant, warrantyClaimFor: String? = null,
    ) = insertRepair(tx, customerId, UUID.randomUUID(), category, issue, brand, model, symptoms, notes, photos, low, high, turnaround, address,
        date, hour, contactName, contactPhone, warrantyClaimFor, status, createdAt)

    internal fun seedEvent(tx: Tx, id: String, status: RepairStatus?, actorType: String, actorName: String, title: String?, message: String?, kind: String, at: Instant) =
        event(tx, id, status, actorType, actorName, title, message, kind, emptyMap(), at)

    internal fun seedHandover(tx: Tx, id: String, stage: String, seal: String, by: String, photos: Int, at: Instant) = handover(tx, id, stage, seal, by, photos, at)
    internal fun seedAddress(tx: Tx, rs: ResultSet) = addressDto(rs)
    internal fun seedInvoice(tx: Tx, id: String, total: Int) = issueInvoice(tx, id, total)
}
