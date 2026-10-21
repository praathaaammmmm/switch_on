package com.switchon.api

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Development only: gives a new guest the five demo journeys the app has always shown (a quote awaiting approval,
 * a paid repair under warranty, a declined quote, an expired warranty and a cancellation), dated relative to today.
 * Runs when APP_ENV=development and SEED_DEMO_DATA=true, or on demand through the internal API.
 */
class DemoSeeder(private val s: Services) {

    private fun at(d: LocalDate, h: Int, m: Int): Instant = d.atTime(LocalTime.of(h, m)).atZone(IST).toInstant()

    private val fixHub = UUID.fromString("7b1d0d0e-2f5c-4b67-9b70-1a2b3c4d5e01")
    private val voltLabs = UUID.fromString("7b1d0d0e-2f5c-4b67-9b70-1a2b3c4d5e02")
    private val chipCare = UUID.fromString("7b1d0d0e-2f5c-4b67-9b70-1a2b3c4d5e03")
    private val ravi = UUID.fromString("3c9e2a10-5d2b-4c1e-8f00-0a0b0c0d0e01")
    private val imran = UUID.fromString("3c9e2a10-5d2b-4c1e-8f00-0a0b0c0d0e02")
    private val manjunath = UUID.fromString("3c9e2a10-5d2b-4c1e-8f00-0a0b0c0d0e03")

    fun seed(customerId: UUID) {
        val today = s.todayIst()
        s.db.tx {
            if (one("SELECT 1 FROM repairs WHERE customer_id = ? LIMIT 1", customerId) { 1 } != null) return@tx
            update("UPDATE customers SET display_name = coalesce(display_name, 'Priya Sharma'), phone = coalesce(phone, '9845012873') WHERE id = ?", customerId)
            val home = address("Home", "Flat 4B, Sai Krupa Apartments", "12th Main Road, HAL 2nd Stage", "Indiranagar", "560038", "Near Indiranagar metro station", true, customerId)
            val work = address("Work", "4th Floor, Prestige Atlanta", "80 Feet Road, 1st Block", "Koramangala", "560034", "Above the Sony World signal", false, customerId)

            // 1. iPhone screen: quote waiting for the customer.
            run {
                val d0 = today.minusDays(2); val d1 = today.minusDays(1)
                val id = s.seedInsert(this, customerId, "PHONE", "SCREEN", "Apple", "iPhone 13", listOf("Cracked glass", "Touch not working"),
                    "Dropped on the stairs. Bottom third of the screen doesn't respond.", 2, 3899, 5299, "24–48 hours", home, d1, 11,
                    "Priya Sharma", "9845012873", RepairStatus.QUOTE_PENDING, at(d0, 21, 14))
                update("UPDATE repairs SET partner_id = ?, shop_id = ?, seal_code = '7Q4K-2190' WHERE id = ?", ravi, fixHub, id)
                ev(id, RepairStatus.PICKUP_REQUESTED, "CUSTOMER", "You", null, "Pickup $d1 11 AM – 1 PM. Estimate ₹3,899 – ₹5,299.", at(d0, 21, 14))
                ev(id, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On", null, null, at(d0, 21, 14))
                ev(id, RepairStatus.PARTNER_ASSIGNED, "SYSTEM", "Switch On", null, "Ravi Kumar · Honda Activa · KA 03 HM 2241", at(d1, 9, 2))
                ev(id, null, "PARTNER", "Ravi Kumar", "Identity checked at the door", "OTP matched", at(d1, 11, 47), "CUSTODY")
                s.seedHandover(this, id, "PICKUP", "7Q4K-2190", "Ravi Kumar", 3, at(d1, 11, 49))
                ev(id, RepairStatus.PICKED_UP, "PARTNER", "Ravi Kumar", "Device sealed and picked up", "Seal 7Q4K-2190 · 3 handover photos", at(d1, 11, 51), "CUSTODY")
                s.seedHandover(this, id, "SHOP_INTAKE", "7Q4K-2190", "FixHub Electronics", 1, at(d1, 12, 34))
                ev(id, RepairStatus.AT_SHOP, "SHOP", "FixHub Electronics", "Handed to repair shop", "Seal intact on arrival · FixHub Electronics", at(d1, 12, 34), "CUSTODY")
                diagnosis(id, "OLED display cracked; touch layer dead along the bottom edge.",
                    listOf("Face ID, True Tone and cameras tested OK", "Mid-frame slightly bent at the bottom-left corner", "Battery health 86%, no action needed"), at(d1, 17, 40))
                ev(id, RepairStatus.DIAGNOSED, "SHOP", "FixHub Electronics", null, "Display cracked, touch layer dead", at(d1, 17, 40))
                quote(id, "90 days on display and labour", at(d1, 17, 46), null, null, null,
                    LineItemDto("OLED display assembly (premium grade)", "Part", "PART", 3650),
                    LineItemDto("Frame straightening and water seal", "Part", "PART", 350),
                    LineItemDto("Labour", "Screen replacement · 70 min bench time", "LABOUR", 700),
                    LineItemDto("Pickup and drop", "Doorstep, both ways", "FEE", 0))
                ev(id, RepairStatus.QUOTE_PENDING, "SHOP", "FixHub Electronics", "Quote sent for approval", "₹4,700 · within your estimate", at(d1, 17, 46), "ALERT")
            }

            // 2. Dell battery: delivered 37 days ago, paid by UPI, warranty running.
            run {
                val delivered = today.minusDays(37); val start = delivered.minusDays(3)
                val id = s.seedInsert(this, customerId, "LAPTOP", "BATTERY", "Dell", "Inspiron 15 3520", listOf("Drains quickly", "Swollen or bulging"), "", 1,
                    2799, 3799, "2–3 days", work, start, 15, "Priya Sharma", "9845012873", RepairStatus.WARRANTY_ACTIVE, at(start.minusDays(1), 20, 3))
                update("UPDATE repairs SET partner_id = ?, shop_id = ?, seal_code = 'M2RT-8841', delivered_at = ? WHERE id = ?", manjunath, chipCare, at(delivered, 18, 22), id)
                ev(id, RepairStatus.PICKUP_REQUESTED, "CUSTOMER", "You", null, "Estimate ₹2,799 – ₹3,799", at(start.minusDays(1), 20, 3))
                ev(id, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On", null, null, at(start.minusDays(1), 20, 3))
                ev(id, RepairStatus.PARTNER_ASSIGNED, "SYSTEM", "Switch On", null, "Manjunath G.", at(start, 9, 10))
                s.seedHandover(this, id, "PICKUP", "M2RT-8841", "Manjunath G.", 2, at(start, 15, 36))
                ev(id, RepairStatus.PICKED_UP, "PARTNER", "Manjunath G.", "Device sealed and picked up", "Seal M2RT-8841", at(start, 15, 36), "CUSTODY")
                ev(id, RepairStatus.AT_SHOP, "SHOP", "ChipCare Service Centre", "Handed to repair shop", "Seal intact on arrival", at(start, 16, 52), "CUSTODY")
                diagnosis(id, "Battery worn to 54% health; swelling starting near the trackpad.", listOf("Charging circuit tested OK", "SSD health 96%", "Trackpad undamaged"), at(start.plusDays(1), 15, 10))
                ev(id, RepairStatus.DIAGNOSED, "SHOP", "ChipCare Service Centre", null, null, at(start.plusDays(1), 15, 10))
                quote(id, "90 days on battery and labour", at(start.plusDays(1), 15, 20), "APPROVED", at(start.plusDays(1), 15, 41), null,
                    LineItemDto("Replacement battery, 41 Wh", "Part", "PART", 2150),
                    LineItemDto("Adhesives and insulation", "Part", "PART", 149),
                    LineItemDto("Labour", "Battery replacement · 45 min", "LABOUR", 600),
                    LineItemDto("Pickup and drop", "Doorstep, both ways", "FEE", 0))
                ev(id, RepairStatus.QUOTE_PENDING, "SHOP", "ChipCare Service Centre", "Quote sent", "₹2,899", at(start.plusDays(1), 15, 20), "ALERT")
                ev(id, RepairStatus.REPAIRING, "CUSTOMER", "You", "Quote approved", "₹2,899", at(start.plusDays(1), 15, 41))
                ev(id, RepairStatus.QUALITY_CHECK, "SHOP", "ChipCare Service Centre", null, "Battery 100% · 6 h 40 min runtime test", at(delivered, 11, 5))
                ev(id, RepairStatus.RETURN_SCHEDULED, "SHOP", "ChipCare Service Centre", "Out for delivery", "Resealed", at(delivered, 16, 40), "CUSTODY")
                ev(id, RepairStatus.DELIVERED, "PARTNER", "Manjunath G.", "Delivered", "Signed for by Priya S.", at(delivered, 18, 22), "CUSTODY")
                ev(id, RepairStatus.PAYMENT_PENDING, "SYSTEM", "Switch On", null, null, at(delivered, 18, 22))
                paid(id, "UPI", 2899, "UPI priya@okhdfcbank · ref 428713390625", at(delivered, 18, 26))
                warranty(id, delivered, "90 days on battery and labour", at(delivered, 18, 26))
            }

            // 3. Samsung tablet: quote declined, returned unrepaired, nothing to pay.
            run {
                val start = today.minusDays(64)
                val id = s.seedInsert(this, customerId, "TABLET", "WATER", "Samsung", "Galaxy Tab S6 Lite", listOf("Spilled drink", "Won't turn on"),
                    "Chai spilled on it overnight.", 0, 2299, 2999, "3–5 days", home, start, 9, "Priya Sharma", "9845012873",
                    RepairStatus.DELIVERED, at(start.minusDays(1), 22, 40))
                update("UPDATE repairs SET partner_id = ?, shop_id = ?, seal_code = 'C9LP-3412', delivered_at = ? WHERE id = ?", imran, voltLabs, at(start.plusDays(3), 17, 15), id)
                ev(id, RepairStatus.PICKUP_REQUESTED, "CUSTOMER", "You", null, "Estimate ₹2,299 – ₹2,999", at(start.minusDays(1), 22, 40))
                ev(id, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On", null, null, at(start.minusDays(1), 22, 40))
                ev(id, RepairStatus.PARTNER_ASSIGNED, "SYSTEM", "Switch On", null, "Imran Pasha", at(start, 8, 30))
                ev(id, RepairStatus.PICKED_UP, "PARTNER", "Imran Pasha", "Device sealed and picked up", "Seal C9LP-3307", at(start, 9, 41), "CUSTODY")
                ev(id, RepairStatus.AT_SHOP, "SHOP", "Volt Labs Repair", "Handed to repair shop", "Seal intact on arrival", at(start, 11, 2), "CUSTODY")
                diagnosis(id, "Liquid reached the main board; display flex corroded.", listOf("Board needs replacement", "Display flex corroded", "Battery tested OK"), at(start.plusDays(2), 13, 0))
                ev(id, RepairStatus.DIAGNOSED, "SHOP", "Volt Labs Repair", null, null, at(start.plusDays(2), 13, 0))
                quote(id, "90 days on parts and labour", at(start.plusDays(2), 13, 10), "DECLINED", at(start.plusDays(2), 19, 2), "Costs more than a new tablet",
                    LineItemDto("Main board (refurbished)", "Part", "PART", 5400),
                    LineItemDto("Display flex cable", "Part", "PART", 650),
                    LineItemDto("Labour", "Board swap · 2 h", "LABOUR", 900),
                    LineItemDto("Pickup and drop", "Doorstep, both ways", "FEE", 0))
                ev(id, RepairStatus.QUOTE_PENDING, "SHOP", "Volt Labs Repair", "Quote sent", "₹6,950 · above your estimate", at(start.plusDays(2), 13, 10), "ALERT")
                ev(id, RepairStatus.QUOTE_DECLINED, "CUSTOMER", "You", null, "Costs more than a new tablet", at(start.plusDays(2), 19, 2))
                ev(id, RepairStatus.RETURN_SCHEDULED, "SHOP", "Volt Labs Repair", "Resealed unrepaired", "Seal C9LP-3412", at(start.plusDays(3), 10, 5), "CUSTODY")
                ev(id, RepairStatus.DELIVERED, "PARTNER", "Imran Pasha", "Returned to you", "Nothing to pay", at(start.plusDays(3), 17, 15), "CUSTODY")
            }

            // 4. Redmi charging port: paid in cash, warranty expired.
            run {
                val delivered = today.minusDays(141)
                val id = s.seedInsert(this, customerId, "PHONE", "CHARGING", "Xiaomi", "Redmi Note 12", listOf("Loose port"), "", 0, 699, 999, "24–48 hours", home,
                    delivered.minusDays(1), 17, "Priya Sharma", "9845012873", RepairStatus.WARRANTY_ACTIVE, at(delivered.minusDays(2), 13, 12))
                update("UPDATE repairs SET partner_id = ?, shop_id = ?, seal_code = 'H4WD-0172', delivered_at = ? WHERE id = ?", ravi, fixHub, at(delivered, 16, 48), id)
                ev(id, RepairStatus.PICKUP_REQUESTED, "CUSTOMER", "You", null, "Estimate ₹699 – ₹999", at(delivered.minusDays(2), 13, 12))
                ev(id, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On", null, null, at(delivered.minusDays(2), 13, 12))
                ev(id, RepairStatus.PICKED_UP, "PARTNER", "Ravi Kumar", "Device sealed and picked up", "Seal H4WD-0172", at(delivered.minusDays(1), 17, 22), "CUSTODY")
                diagnosis(id, "USB-C port pins worn.", listOf("Battery health 91%"), at(delivered.minusDays(1), 20, 0))
                quote(id, "90 days on parts and labour", at(delivered.minusDays(1), 20, 5), "APPROVED", at(delivered.minusDays(1), 20, 30), null,
                    LineItemDto("USB-C charging port board", "Part", "PART", 450),
                    LineItemDto("Labour", "Port replacement · 30 min", "LABOUR", 300),
                    LineItemDto("Pickup and drop", "Doorstep, both ways", "FEE", 0))
                ev(id, RepairStatus.REPAIRING, "CUSTOMER", "You", "Quote approved", "₹750", at(delivered.minusDays(1), 20, 30))
                ev(id, RepairStatus.DELIVERED, "PARTNER", "Ravi Kumar", "Delivered", "Signed for by Priya S.", at(delivered, 16, 48), "CUSTODY")
                paid(id, "CASH", 750, "Cash to Ravi Kumar", at(delivered, 16, 50))
                warranty(id, delivered, "90 days on parts and labour", at(delivered, 16, 50))
            }

            // 5. boAt earbuds: cancelled before pickup.
            run {
                val d = today.minusDays(77)
                val id = s.seedInsert(this, customerId, "AUDIO", "BATTERY", "boAt", "Airdopes 141", listOf("Drains quickly"), "", 0, 699, 999, "2–4 days", home,
                    d.plusDays(1), 13, "Priya Sharma", "9845012873", RepairStatus.CANCELLED, at(d, 10, 4))
                update("UPDATE repairs SET cancelled_at = ?, cancel_reason = 'Bought a replacement instead' WHERE id = ?", at(d, 10, 31), id)
                ev(id, RepairStatus.PICKUP_REQUESTED, "CUSTOMER", "You", null, "Estimate ₹699 – ₹999", at(d, 10, 4))
                ev(id, RepairStatus.PICKUP_SCHEDULED, "SYSTEM", "Switch On", null, null, at(d, 10, 4))
                ev(id, RepairStatus.CANCELLED, "CUSTOMER", "You", null, "Bought a replacement instead · no charge", at(d, 10, 31))
            }
        }
    }

    // ---- helpers (run inside the seeding transaction) ----

    private fun Tx.address(label: String, l1: String, l2: String, area: String, pin: String, landmark: String, default: Boolean, customer: UUID): AddressDto {
        val id = UUID.randomUUID()
        update("INSERT INTO addresses (id, customer_id, label, line1, line2, area, city, pincode, landmark, is_default) VALUES (?,?,?,?,?,?,'Bengaluru',?,?,?)",
            id, customer, label, l1, l2, area, pin, landmark, default)
        return one("SELECT * FROM addresses WHERE id = ?", id) { s.seedAddress(this, it) }!!
    }

    private fun Tx.ev(id: String, status: RepairStatus?, actorType: String, actor: String, title: String?, message: String?, at: Instant, kind: String = if (status == RepairStatus.QUOTE_PENDING) "ALERT" else "STATUS") =
        s.seedEvent(this, id, status, actorType, actor, title, message, kind, at)

    private fun Tx.diagnosis(id: String, summary: String, findings: List<String>, at: Instant) =
        update("INSERT INTO diagnoses (repair_id, summary, findings, diagnosed_at) VALUES (?,?,?,?)", id, summary, findings, at)

    private fun Tx.quote(id: String, warranty: String, sent: Instant, decision: String?, decided: Instant?, reason: String?, vararg items: LineItemDto) {
        val qid = UUID.randomUUID()
        update("INSERT INTO quotes (id, repair_id, warranty_text, total_inr, sent_at, decision, decided_at, decline_reason) VALUES (?,?,?,?,?,?,?,?)",
            qid, id, warranty, items.sumOf { it.amount }, sent, decision, decided, reason)
        items.forEachIndexed { i, l ->
            update("INSERT INTO quote_line_items (id, quote_id, label, detail, kind, amount_inr, sort_order) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), qid, l.label, l.detail, l.kind, l.amount, i)
        }
    }

    private fun Tx.paid(id: String, method: String, amount: Int, ref: String, at: Instant) {
        update("INSERT INTO payment_records (id, repair_id, method, amount_inr, reference, idempotency_key, recorded_at) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(), id, method, amount, ref, UUID.randomUUID(), at)
        ev(id, RepairStatus.PAYMENT_COMPLETED, "CUSTOMER", "You", "Paid by ${if (method == "UPI") "UPI" else method.lowercase().replaceFirstChar { it.uppercase() }}", "₹${s.inr(amount)} · $ref", at, "PAYMENT")
        s.seedInvoice(this, id, amount)
        update("UPDATE invoices SET issued_at = ? WHERE repair_id = ?", at, id)
    }

    private fun Tx.warranty(id: String, delivered: LocalDate, coverage: String, at: Instant) {
        update("INSERT INTO warranties (repair_id, starts_on, ends_on, coverage) VALUES (?,?,?,?)", id, delivered, delivered.plusDays(WARRANTY_DAYS), coverage)
        ev(id, RepairStatus.WARRANTY_ACTIVE, "SYSTEM", "Switch On", null, "Covered until ${delivered.plusDays(WARRANTY_DAYS)}.", at)
    }
}
