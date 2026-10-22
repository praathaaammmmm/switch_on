package com.switchon.api

import com.switchon.api.RepairStatus.*
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * Services & pricing, partners, and pickup capacity. Price changes need a reason and write one history row per
 * changed field. Repairs keep the estimate they were booked with and quotes keep their own line items, so editing
 * the catalogue never rewrites what a customer has already been told.
 */
class AdminCatalog(private val s: Services) {
    private val db = s.db
    private val repairs = AdminRepairs(s)

    // ==========================================================================================================
    // Catalogue
    // ==========================================================================================================

    private fun entryRow(rs: ResultSet) = ServiceEntryDto(
        rs.getString("category_code"), rs.getString("issue_code"), rs.getString("label"), rs.getInt("base_price_inr"), rs.getInt("diagnosis_fee_inr"),
        rs.getString("turnaround"), rs.getString("label_override"), rs.getBoolean("active"), rs.instant("updated_at").toString(), rs.getInt("bookings"),
    )

    private val entrySelect = """
        SELECT e.*, coalesce(e.label_override, i.label) AS label,
               (SELECT count(*) FROM repairs r WHERE r.device_category = e.category_code AND r.issue_code = e.issue_code) AS bookings
        FROM service_catalog_entries e JOIN repair_issues i ON i.code = e.issue_code
        JOIN device_categories c ON c.code = e.category_code
    """.trimIndent()

    fun catalog(): CatalogAdminDto = db.tx {
        CatalogAdminDto(
            categories = query("SELECT * FROM device_categories ORDER BY sort_order") {
                CategoryAdminDto(it.getString("code"), it.getString("label"), it.getInt("sort_order"), it.getBoolean("active"))
            },
            issues = query("SELECT * FROM repair_issues ORDER BY sort_order") {
                IssueAdminDto(it.getString("code"), it.getString("label"), it.getString("hint"), it.getInt("sort_order"))
            },
            entries = query("$entrySelect ORDER BY c.sort_order, i.sort_order", map = ::entryRow),
            brands = query("SELECT b.* FROM device_brands b JOIN device_categories c ON c.code = b.category_code ORDER BY c.sort_order, b.sort_order") {
                BrandAdminDto(it.getInt("id"), it.getString("category_code"), it.getString("name"), it.getDouble("price_multiplier"))
            },
        )
    }

    fun service(category: String, issue: String): ServiceDetailDto = db.tx { serviceIn(this, category, issue) }

    private fun serviceIn(tx: Tx, category: String, issue: String): ServiceDetailDto = with(tx) {
        val entry = one("$entrySelect WHERE e.category_code = ? AND e.issue_code = ?", category, issue, map = ::entryRow)
            ?: throw notFound("That service isn't in the catalogue.")
        val brands = query("SELECT name, price_multiplier FROM device_brands WHERE category_code = ? ORDER BY sort_order", category) { it.getString(1) to it.getDouble(2) }
        val estimates = listOf(Pricing.estimate(entry.basePrice, null).let { EstimatePreviewDto(null, it.first, it.second) }) +
            brands.map { (b, m) -> Pricing.estimate(entry.basePrice, m).let { EstimatePreviewDto(b, it.first, it.second) } }
        ServiceDetailDto(entry, estimates, historyIn(this, category, issue, null, 100))
    }

    fun history(category: String?, issue: String?, limit: Int): List<PriceHistoryDto> = db.tx { historyIn(this, category, issue, null, limit) }

    private fun historyIn(tx: Tx, category: String?, issue: String?, brand: String?, limit: Int): List<PriceHistoryDto> = with(tx) {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        category?.takeIf { it.isNotBlank() }?.let { where += "h.category_code = ?"; args += it }
        issue?.takeIf { it.isNotBlank() }?.let { where += "h.issue_code = ?"; args += it }
        brand?.let { where += "h.brand = ?"; args += it }
        query(
            "SELECT h.*, u.email FROM service_price_history h JOIN admin_users u ON u.id = h.changed_by" +
                (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY h.id DESC LIMIT ${limit.coerceIn(1, 500)}",
            *args.toTypedArray(),
        ) {
            PriceHistoryDto(it.getLong("id"), it.getString("scope"), it.getString("category_code"), it.getString("issue_code"), it.getString("brand"),
                it.getString("field"), it.getString("old_value"), it.getString("new_value"), it.getString("reason"), it.getString("email"),
                it.instant("changed_at").toString())
        }
    }

    private fun requireReason(reason: String): String = reason.trim().also {
        if (it.length < 5) throw badRequest("Give a reason of at least 5 characters for this change.", "reason_required")
        if (it.length > 300) throw badRequest("Keep the reason under 300 characters.")
    }

    private fun validTurnaround(t: String) = t.trim().also { if (it.isEmpty() || it.length > 40) throw badRequest("Turnaround is 1 to 40 characters, e.g. 24–48 hours.") }

    private fun recordHistory(tx: Tx, p: AdminPrincipal, scope: String, category: String, issue: String?, brand: String?, field: String, old: Any?, new: Any?, reason: String) =
        tx.update(
            "INSERT INTO service_price_history (scope, category_code, issue_code, brand, field, old_value, new_value, reason, changed_by, changed_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            scope, category, issue, brand, field, old?.toString(), new?.toString(), reason, p.id, s.now(),
        )

    /** Changes one service. Only the fields given change; each one is recorded in the price history with the reason. */
    fun updateService(p: AdminPrincipal, category: String, issue: String, req: ServiceUpdateRequest): ServiceDetailDto {
        p.require(Permission.PRICING_MANAGE)
        val reason = requireReason(req.reason)
        req.basePrice?.let { if (it !in 1..500_000) throw badRequest("Base price must be between ₹1 and ₹5,00,000.") }
        req.diagnosisFee?.let { if (it !in 0..50_000) throw badRequest("Diagnosis fee must be between ₹0 and ₹50,000.") }
        return db.tx {
            val cur = one("SELECT * FROM service_catalog_entries WHERE category_code = ? AND issue_code = ? FOR UPDATE", category, issue) {
                mapOf("basePrice" to it.getInt("base_price_inr"), "diagnosisFee" to it.getInt("diagnosis_fee_inr"), "turnaround" to it.getString("turnaround"),
                    "labelOverride" to it.getString("label_override"), "active" to it.getBoolean("active"))
            } ?: throw notFound("That service isn't in the catalogue.")
            val next = cur.toMutableMap()
            req.basePrice?.let { next["basePrice"] = it }
            req.diagnosisFee?.let { next["diagnosisFee"] = it }
            req.turnaround?.let { next["turnaround"] = validTurnaround(it) }
            if (req.clearLabelOverride) next["labelOverride"] = null
            else req.labelOverride?.trim()?.takeIf { it.isNotEmpty() }?.let { next["labelOverride"] = it.take(40) }
            req.active?.let { next["active"] = it }
            val changed = next.filter { (k, v) -> cur[k] != v }
            if (changed.isEmpty()) throw badRequest("Nothing changed.", "no_change")
            update(
                "UPDATE service_catalog_entries SET base_price_inr = ?, diagnosis_fee_inr = ?, turnaround = ?, label_override = ?, active = ?, updated_at = ? WHERE category_code = ? AND issue_code = ?",
                next["basePrice"], next["diagnosisFee"], next["turnaround"], next["labelOverride"], next["active"], s.now(), category, issue,
            )
            changed.forEach { (field, v) -> recordHistory(this, p, "SERVICE", category, issue, null, field, cur[field], v, reason) }
            audit(this, p, "pricing.service_update", "service", "$category/$issue", reason, cur.filterKeys { it in changed }, changed)
            serviceIn(this, category, issue)
        }
    }

    fun createService(p: AdminPrincipal, req: ServiceCreateRequest): ServiceDetailDto {
        p.require(Permission.PRICING_MANAGE)
        val reason = requireReason(req.reason)
        if (req.basePrice !in 1..500_000) throw badRequest("Base price must be between ₹1 and ₹5,00,000.")
        if (req.diagnosisFee !in 0..50_000) throw badRequest("Diagnosis fee must be between ₹0 and ₹50,000.")
        val turnaround = validTurnaround(req.turnaround)
        return db.tx {
            if (one("SELECT 1 FROM device_categories WHERE code = ?", req.category) { 1 } == null) throw badRequest("Unknown device category.")
            if (one("SELECT 1 FROM repair_issues WHERE code = ?", req.issue) { 1 } == null) throw badRequest("Unknown repair issue.")
            if (one("SELECT 1 FROM service_catalog_entries WHERE category_code = ? AND issue_code = ?", req.category, req.issue) { 1 } != null)
                throw conflict("already_offered", "That repair is already in the catalogue. Edit it instead.")
            val label = req.labelOverride?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
            update(
                "INSERT INTO service_catalog_entries (category_code, issue_code, base_price_inr, diagnosis_fee_inr, turnaround, label_override, active, updated_at) VALUES (?,?,?,?,?,?,true,?)",
                req.category, req.issue, req.basePrice, req.diagnosisFee, turnaround, label, s.now(),
            )
            recordHistory(this, p, "SERVICE", req.category, req.issue, null, "basePrice", null, req.basePrice, reason)
            if (req.diagnosisFee > 0) recordHistory(this, p, "SERVICE", req.category, req.issue, null, "diagnosisFee", null, req.diagnosisFee, reason)
            audit(this, p, "pricing.service_create", "service", "${req.category}/${req.issue}", reason, null,
                mapOf("basePrice" to req.basePrice, "diagnosisFee" to req.diagnosisFee, "turnaround" to turnaround, "labelOverride" to label))
            serviceIn(this, req.category, req.issue)
        }
    }

    fun updateCategory(p: AdminPrincipal, code: String, req: CategoryUpdateRequest): CatalogAdminDto {
        p.require(Permission.PRICING_MANAGE)
        val reason = requireReason(req.reason)
        db.tx {
            val cur = one("SELECT * FROM device_categories WHERE code = ? FOR UPDATE", code) {
                mapOf("label" to it.getString("label"), "active" to it.getBoolean("active"), "sortOrder" to it.getInt("sort_order"))
            } ?: throw notFound("Device category not found.")
            val next = cur.toMutableMap()
            req.label?.trim()?.takeIf { it.isNotEmpty() }?.let { next["label"] = it.take(30) }
            req.active?.let { next["active"] = it }
            req.sortOrder?.let { next["sortOrder"] = it.coerceIn(0, 99) }
            val changed = next.filter { (k, v) -> cur[k] != v }
            if (changed.isEmpty()) throw badRequest("Nothing changed.", "no_change")
            update("UPDATE device_categories SET label = ?, active = ?, sort_order = ? WHERE code = ?", next["label"], next["active"], next["sortOrder"], code)
            if ("active" in changed) recordHistory(this, p, "SERVICE", code, null, null, "categoryActive", cur["active"], next["active"], reason)
            audit(this, p, "pricing.category_update", "category", code, reason, cur.filterKeys { it in changed }, changed)
        }
        return catalog()
    }

    fun updateIssue(p: AdminPrincipal, code: String, req: IssueUpdateRequest): CatalogAdminDto {
        p.require(Permission.PRICING_MANAGE)
        val reason = requireReason(req.reason)
        db.tx {
            val cur = one("SELECT label, hint FROM repair_issues WHERE code = ? FOR UPDATE", code) { mapOf("label" to it.getString(1), "hint" to it.getString(2)) }
                ?: throw notFound("Repair issue not found.")
            val next = cur.toMutableMap()
            req.label?.trim()?.takeIf { it.isNotEmpty() }?.let { next["label"] = it.take(30) }
            req.hint?.trim()?.takeIf { it.isNotEmpty() }?.let { next["hint"] = it.take(60) }
            val changed = next.filter { (k, v) -> cur[k] != v }
            if (changed.isEmpty()) throw badRequest("Nothing changed.", "no_change")
            update("UPDATE repair_issues SET label = ?, hint = ? WHERE code = ?", next["label"], next["hint"], code)
            audit(this, p, "pricing.issue_update", "issue", code, reason, cur.filterKeys { it in changed }, changed)
        }
        return catalog()
    }

    /** Brand multipliers change every estimate for that brand, so they follow the same reason-and-history rule. */
    fun updateBrand(p: AdminPrincipal, id: Int, req: BrandUpdateRequest): CatalogAdminDto {
        p.require(Permission.PRICING_MANAGE)
        val reason = requireReason(req.reason)
        if (req.multiplier !in 0.3..5.0) throw badRequest("Multiplier must be between 0.30 and 5.00.")
        val m = Math.round(req.multiplier * 100) / 100.0
        db.tx {
            val (category, name, old) = one("SELECT category_code, name, price_multiplier FROM device_brands WHERE id = ? FOR UPDATE", id) {
                Triple(it.getString(1), it.getString(2), it.getDouble(3))
            } ?: throw notFound("Brand not found.")
            if (old == m) throw badRequest("Nothing changed.", "no_change")
            update("UPDATE device_brands SET price_multiplier = ? WHERE id = ?", m, id)
            recordHistory(this, p, "BRAND", category, null, name, "multiplier", old, m, reason)
            audit(this, p, "pricing.brand_update", "brand", "$category/$name", reason, mapOf("multiplier" to old), mapOf("multiplier" to m))
        }
        return catalog()
    }

    // ==========================================================================================================
    // Partners
    // ==========================================================================================================

    private val shopJobStatuses = listOf(PICKED_UP, AT_SHOP, DIAGNOSED, QUOTE_PENDING, REPAIRING, QUALITY_CHECK).map { it.name }
    private val courierJobStatuses = listOf(PICKUP_SCHEDULED, PARTNER_ASSIGNED, PICKUP_FAILED, RETURN_SCHEDULED).map { it.name }

    fun partners(): PartnersDto = db.tx {
        val techs = query(
            "SELECT t.*, (SELECT count(*) FROM repairs r WHERE r.technician_id = t.id AND r.status = ANY(?)) AS jobs FROM technicians t ORDER BY t.name", shopJobStatuses,
        ) { TechnicianDto(it.uuid("id").toString(), it.uuid("shop_id").toString(), it.getString("name"), it.strings("skills"), it.getBoolean("active"), it.getInt("jobs")) }
            .groupBy { it.shopId }
        PartnersDto(
            shops = query("SELECT s.*, (SELECT count(*) FROM repairs r WHERE r.shop_id = s.id AND r.status = ANY(?)) AS jobs FROM repair_shops s ORDER BY s.name", shopJobStatuses) {
                val id = it.uuid("id").toString()
                ShopAdminDto(id, it.getString("name"), it.getString("area"), it.getDouble("rating"), it.getInt("reviews"), it.getString("verified_since"),
                    it.getBoolean("active"), it.getInt("capacity"), it.strings("capabilities"), it.getInt("jobs"), techs[id].orEmpty())
            },
            couriers = query(
                """SELECT p.*, (SELECT count(*) FROM repairs r WHERE r.partner_id = p.id AND r.slot_date = ? AND r.status = ANY(?)) AS today,
                          (SELECT count(*) FROM repairs r WHERE r.partner_id = p.id AND r.status = ANY(?)) AS jobs
                   FROM pickup_partners p ORDER BY p.name""",
                s.todayIst(), courierJobStatuses.take(3), courierJobStatuses,
            ) {
                CourierDto(it.uuid("id").toString(), it.getString("name"), it.getString("phone"), it.getString("vehicle"), it.getDouble("rating"), it.getInt("trips"),
                    it.getBoolean("active"), it.getInt("daily_capacity"), it.getInt("today"), it.getInt("jobs"))
            },
        )
    }

    fun assignments(kind: String, id: String): List<RepairSummary> {
        val uuid = parseUuid(id, "id")
        val column = when (kind) { "shops" -> "r.shop_id"; "couriers" -> "r.partner_id"; "technicians" -> "r.technician_id"; else -> throw notFound() }
        return db.tx { query("${repairs.summarySelect} WHERE $column = ? AND r.status = ANY(?) ORDER BY r.slot_date, r.slot_start_hour", uuid, ACTIVE_STATUSES, map = repairs::summary) }
    }

    private val categoryCodes by lazy { db.tx { query("SELECT code FROM device_categories") { it.getString(1) }.toSet() } }

    private fun validCapabilities(list: List<String>): List<String> = list.map { it.trim().uppercase() }.distinct().also { caps ->
        caps.firstOrNull { it !in categoryCodes }?.let { throw badRequest("Unknown device category $it.") }
    }

    fun upsertShop(p: AdminPrincipal, id: String?, req: ShopUpsertRequest): PartnersDto {
        p.require(Permission.PARTNERS_MANAGE)
        if (req.name.isBlank() || req.area.isBlank()) throw badRequest("Name and area are required.")
        if (req.capacity !in 0..500) throw badRequest("Capacity must be between 0 and 500 jobs.")
        val caps = validCapabilities(req.capabilities)
        db.tx {
            val after = mapOf("name" to req.name.trim(), "area" to req.area.trim(), "capacity" to req.capacity, "capabilities" to caps, "active" to req.active)
            if (id == null) {
                val sid = UUID.randomUUID()
                update("INSERT INTO repair_shops (id, name, area, rating, reviews, technician, verified_since, active, capacity, capabilities) VALUES (?,?,?,0,0,'',?,?,?,?)",
                    sid, req.name.trim(), req.area.trim(), req.verifiedSince.trim().ifEmpty { s.todayIst().year.toString() }, req.active, req.capacity, caps)
                audit(this, p, "partners.shop_create", "shop", sid.toString(), req.reason.trim().ifEmpty { null }, null, after)
            } else {
                val sid = parseUuid(id, "id")
                val before = one("SELECT * FROM repair_shops WHERE id = ? FOR UPDATE", sid) {
                    mapOf("name" to it.getString("name"), "area" to it.getString("area"), "capacity" to it.getInt("capacity"),
                        "capabilities" to it.strings("capabilities"), "active" to it.getBoolean("active"))
                } ?: throw notFound("Repair shop not found.")
                update("UPDATE repair_shops SET name = ?, area = ?, capacity = ?, capabilities = ?, active = ? WHERE id = ?",
                    req.name.trim(), req.area.trim(), req.capacity, caps, req.active, sid)
                audit(this, p, "partners.shop_update", "shop", sid.toString(), req.reason.trim().ifEmpty { null }, before, after)
            }
        }
        return partners()
    }

    fun upsertTechnician(p: AdminPrincipal, id: String?, req: TechnicianUpsertRequest): PartnersDto {
        p.require(Permission.PARTNERS_MANAGE)
        if (req.name.isBlank()) throw badRequest("Name is required.")
        val shop = parseUuid(req.shopId, "shopId")
        val skills = validCapabilities(req.skills)
        db.tx {
            if (one("SELECT 1 FROM repair_shops WHERE id = ?", shop) { 1 } == null) throw notFound("Repair shop not found.")
            val after = mapOf("shopId" to shop.toString(), "name" to req.name.trim(), "skills" to skills, "active" to req.active)
            if (id == null) {
                val tid = UUID.randomUUID()
                update("INSERT INTO technicians (id, shop_id, name, skills, active) VALUES (?,?,?,?,?)", tid, shop, req.name.trim(), skills, req.active)
                audit(this, p, "partners.technician_create", "technician", tid.toString(), req.reason.trim().ifEmpty { null }, null, after)
            } else {
                val tid = parseUuid(id, "id")
                val before = one("SELECT * FROM technicians WHERE id = ? FOR UPDATE", tid) {
                    mapOf("shopId" to it.uuid("shop_id").toString(), "name" to it.getString("name"), "skills" to it.strings("skills"), "active" to it.getBoolean("active"))
                } ?: throw notFound("Technician not found.")
                if (before["shopId"] != shop.toString() &&
                    one("SELECT 1 FROM repairs WHERE technician_id = ? AND status = ANY(?)", tid, shopJobStatuses) { 1 } != null)
                    throw conflict("technician_busy", "Reassign this technician's active jobs before moving them to another shop.")
                update("UPDATE technicians SET shop_id = ?, name = ?, skills = ?, active = ? WHERE id = ?", shop, req.name.trim(), skills, req.active, tid)
                audit(this, p, "partners.technician_update", "technician", tid.toString(), req.reason.trim().ifEmpty { null }, before, after)
            }
        }
        return partners()
    }

    fun upsertCourier(p: AdminPrincipal, id: String?, req: CourierUpsertRequest): PartnersDto {
        p.require(Permission.PARTNERS_MANAGE)
        if (req.name.isBlank() || req.vehicle.isBlank()) throw badRequest("Name and vehicle are required.")
        if (!Regex("^[6-9][0-9]{9}$").matches(req.phone.filter(Char::isDigit).takeLast(10))) throw badRequest("Enter a 10-digit Indian mobile number.")
        if (req.dailyCapacity !in 0..100) throw badRequest("Daily capacity must be between 0 and 100 pickups.")
        val phone = "+91 " + req.phone.filter(Char::isDigit).takeLast(10).let { "${it.take(5)} ${it.drop(5)}" }
        db.tx {
            val after = mapOf("name" to req.name.trim(), "phone" to phone, "vehicle" to req.vehicle.trim(), "dailyCapacity" to req.dailyCapacity, "active" to req.active)
            if (id == null) {
                val pid = UUID.randomUUID()
                update("INSERT INTO pickup_partners (id, name, phone, vehicle, rating, trips, active, daily_capacity) VALUES (?,?,?,?,0,0,?,?)",
                    pid, req.name.trim(), phone, req.vehicle.trim(), req.active, req.dailyCapacity)
                audit(this, p, "partners.courier_create", "courier", pid.toString(), req.reason.trim().ifEmpty { null }, null, after)
            } else {
                val pid = parseUuid(id, "id")
                val before = one("SELECT * FROM pickup_partners WHERE id = ? FOR UPDATE", pid) {
                    mapOf("name" to it.getString("name"), "phone" to it.getString("phone"), "vehicle" to it.getString("vehicle"),
                        "dailyCapacity" to it.getInt("daily_capacity"), "active" to it.getBoolean("active"))
                } ?: throw notFound("Pickup partner not found.")
                update("UPDATE pickup_partners SET name = ?, phone = ?, vehicle = ?, daily_capacity = ?, active = ? WHERE id = ?",
                    req.name.trim(), phone, req.vehicle.trim(), req.dailyCapacity, req.active, pid)
                audit(this, p, "partners.courier_update", "courier", pid.toString(), req.reason.trim().ifEmpty { null }, before, after)
            }
        }
        return partners()
    }

    // ==========================================================================================================
    // Pickups & service areas
    // ==========================================================================================================

    fun areas(): AreasDto = db.tx {
        AreasDto(
            areas = query("SELECT * FROM service_areas ORDER BY city, pincode_prefix") { ServiceAreaDto(it.getString("pincode_prefix"), it.getString("city"), it.getBoolean("active")) },
            templates = query("SELECT * FROM pickup_slot_templates ORDER BY start_hour") { SlotTemplateDto(it.getInt(1), it.getInt(2)) },
            blackouts = query("SELECT * FROM pickup_slot_blocks WHERE slot_date >= ? ORDER BY slot_date, start_hour", s.todayIst()) {
                BlackoutDto(it.date("slot_date").toString(), it.getInt("start_hour"), it.getString("reason"))
            },
        )
    }

    fun upsertArea(p: AdminPrincipal, req: AreaUpsertRequest): AreasDto {
        p.require(Permission.AREAS_MANAGE)
        if (!Regex("^[1-9][0-9]{2}$").matches(req.prefix)) throw badRequest("A service area is the first 3 digits of a pincode, e.g. 560.")
        if (req.city.isBlank()) throw badRequest("City is required.")
        db.tx {
            val before = one("SELECT city, active FROM service_areas WHERE pincode_prefix = ? FOR UPDATE", req.prefix) { mapOf("city" to it.getString(1), "active" to it.getBoolean(2)) }
            update("INSERT INTO service_areas (pincode_prefix, city, active) VALUES (?,?,?) ON CONFLICT (pincode_prefix) DO UPDATE SET city = EXCLUDED.city, active = EXCLUDED.active",
                req.prefix, req.city.trim(), req.active)
            audit(this, p, if (before == null) "areas.create" else "areas.update", "service_area", req.prefix, req.reason.trim().ifEmpty { null },
                before, mapOf("city" to req.city.trim(), "active" to req.active))
        }
        return areas()
    }

    fun setSlotTemplate(p: AdminPrincipal, req: SlotTemplateRequest): AreasDto {
        p.require(Permission.AREAS_MANAGE)
        if (req.startHour !in 6..20) throw badRequest("Pickup windows start between 6 AM and 8 PM.")
        if (req.capacity !in 0..200) throw badRequest("Capacity must be between 0 and 200 pickups per window.")
        db.tx {
            val before = one("SELECT capacity FROM pickup_slot_templates WHERE start_hour = ?", req.startHour) { it.getInt(1) }
            update("INSERT INTO pickup_slot_templates (start_hour, capacity) VALUES (?,?) ON CONFLICT (start_hour) DO UPDATE SET capacity = EXCLUDED.capacity",
                req.startHour, req.capacity)
            audit(this, p, "areas.slot_capacity", "slot_template", req.startHour.toString(), req.reason.trim().ifEmpty { null },
                before?.let { mapOf("capacity" to it) }, mapOf("capacity" to req.capacity))
        }
        return areas()
    }

    /** Blocks one window, or every window that day when [BlackoutRequest.startHour] is null. Existing bookings stay; staff reschedule them. */
    fun addBlackout(p: AdminPrincipal, req: BlackoutRequest): AreasDto {
        p.require(Permission.AREAS_MANAGE)
        val date = parseDate(req.date)
        val reason = req.reason.trim()
        if (reason.length < 3) throw badRequest("Say why the window is blocked (the reason is kept with it).")
        if (date.isBefore(s.todayIst())) throw badRequest("Choose today or a later date.")
        db.tx {
            val hours = req.startHour?.let { listOf(it) } ?: query("SELECT start_hour FROM pickup_slot_templates ORDER BY start_hour") { it.getInt(1) }
            if (hours.isEmpty()) throw badRequest("There are no pickup windows to block.")
            hours.forEach { h ->
                update("INSERT INTO pickup_slot_blocks (slot_date, start_hour, reason) VALUES (?,?,?) ON CONFLICT (slot_date, start_hour) DO UPDATE SET reason = EXCLUDED.reason",
                    date, h, reason.take(120))
            }
            audit(this, p, "areas.blackout_add", "slot_block", "$date" + (req.startHour?.let { "@$it" } ?: ""), reason, null, mapOf("date" to date.toString(), "hours" to hours))
        }
        return areas()
    }

    fun removeBlackout(p: AdminPrincipal, date: String, startHour: Int, reason: String): AreasDto {
        p.require(Permission.AREAS_MANAGE)
        val d = parseDate(date)
        val why = reason.trim()
        if (why.length < 3) throw badRequest("Say why the window is reopening.")
        db.tx {
            val old = one("SELECT reason FROM pickup_slot_blocks WHERE slot_date = ? AND start_hour = ?", d, startHour) { it.getString(1) }
                ?: throw notFound("That window isn't blocked.")
            update("DELETE FROM pickup_slot_blocks WHERE slot_date = ? AND start_hour = ?", d, startHour)
            audit(this, p, "areas.blackout_remove", "slot_block", "$d@$startHour", why, mapOf("reason" to old), null)
        }
        return areas()
    }

    /** One day of logistics: each window's load against capacity, the pickups due and the returns out for delivery. */
    fun logistics(date: LocalDate): LogisticsDayDto = db.tx {
        val beforePickup = listOf(PICKUP_REQUESTED, PICKUP_SCHEDULED, PARTNER_ASSIGNED).map { it.name }
        val booked = query("SELECT slot_start_hour, count(*) FROM repairs WHERE slot_date = ? AND status = ANY(?) GROUP BY slot_start_hour", date, beforePickup) {
            it.getInt(1) to it.getInt(2)
        }.toMap()
        val blocks = query("SELECT start_hour, reason FROM pickup_slot_blocks WHERE slot_date = ?", date) { it.getInt(1) to it.getString(2) }.toMap()
        LogisticsDayDto(
            date = date.toString(),
            slots = query("SELECT start_hour, capacity FROM pickup_slot_templates ORDER BY start_hour") { rs ->
                val h = rs.getInt(1)
                SlotLoadDto(h, rs.getInt(2), booked[h] ?: 0, h in blocks, blocks[h])
            },
            pickups = query("${repairs.summarySelect} WHERE r.slot_date = ? AND r.status = ANY(?) ORDER BY r.slot_start_hour, r.id",
                date, beforePickup + PICKUP_FAILED.name, map = repairs::summary),
            deliveries = query("${repairs.summarySelect} WHERE r.status = 'RETURN_SCHEDULED' ORDER BY r.updated_at", map = repairs::summary),
        )
    }
}
