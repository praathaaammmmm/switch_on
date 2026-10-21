package com.switchon.customer.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Seed data. Dates are relative to today so the prototype stays believable whenever it's opened. */
object MockData {

    val profile = Profile(name = "Priya Sharma", phone = "+91 98450 12873", email = "priya.sharma@gmail.com")

    val home = Address(
        id = "addr-home", label = "Home", line1 = "Flat 4B, Sai Krupa Apartments", line2 = "12th Main Road, HAL 2nd Stage",
        area = "Indiranagar", city = "Bengaluru", pincode = "560038", landmark = "Near Indiranagar metro station", isDefault = true,
    )
    val work = Address(
        id = "addr-work", label = "Work", line1 = "4th Floor, Prestige Atlanta", line2 = "80 Feet Road, 1st Block",
        area = "Koramangala", city = "Bengaluru", pincode = "560034", landmark = "Above the Sony World signal",
    )

    val couriers = listOf(
        Courier("Ravi Kumar", "+91 98860 44312", "Honda Activa · KA 03 HM 2241", 4.9, 1312),
        Courier("Imran Pasha", "+91 99003 18765", "TVS Jupiter · KA 01 JK 8834", 4.8, 874),
        Courier("Manjunath G.", "+91 97411 20456", "Hero Splendor · KA 05 EX 1907", 4.9, 2046),
    )

    val shops = listOf(
        Shop("FixHub Electronics", "HAL 2nd Stage, Indiranagar", 4.8, 2140, "Suresh N. · Level 3 technician", "Mar 2024"),
        Shop("Volt Labs Repair", "5th Block, Koramangala", 4.7, 1386, "Deepa R. · Board-level specialist", "Jul 2024"),
        Shop("ChipCare Service Centre", "Sector 2, HSR Layout", 4.6, 952, "Arjun M. · Laptop specialist", "Jan 2025"),
    )

    private fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, LocalTime.of(h, m))

    fun repairs(today: LocalDate = LocalDate.now()): List<Repair> = listOf(active(today), dell(today), declinedTab(today), redmi(today), cancelledBuds(today))

    /** The live order: an iPhone screen job waiting on the customer's quote decision. */
    private fun active(today: LocalDate): Repair {
        val d0 = today.minusDays(2); val d1 = today.minusDays(1)
        val courier = couriers[0]; val shop = shops[0]
        return Repair(
            id = "SO-482917", device = DeviceType.PHONE, issue = IssueType.SCREEN, brand = "Apple", model = "iPhone 13",
            symptoms = listOf("Cracked glass", "Touch not working"), notes = "Dropped on the stairs. Bottom third of the screen doesn't respond.",
            photoCount = 2, estimateLow = 3899, estimateHigh = 5299, turnaround = "24–48 hours",
            address = home, slot = Slot(d1, 11), createdAt = at(d0, 21, 14), stage = Stage.QUOTE_PENDING,
            courier = courier, shop = shop, sealCode = "7Q4K-2190",
            quote = Quote(
                diagnosis = "OLED display cracked; touch layer dead along the bottom edge.",
                findings = listOf(
                    "Face ID, True Tone and cameras tested OK",
                    "Mid-frame slightly bent at the bottom-left corner",
                    "Battery health 86%, no action needed",
                ),
                items = listOf(
                    LineItem("OLED display assembly (premium grade)", "Part", 3650, ItemKind.PART),
                    LineItem("Frame straightening and water seal", "Part", 350, ItemKind.PART),
                    LineItem("Labour", "Screen replacement · 70 min bench time", 700, ItemKind.LABOUR),
                    LineItem("Pickup and drop", "Doorstep, both ways", 0, ItemKind.FEE),
                ),
                warranty = "90 days on display and labour",
                sentAt = at(d1, 17, 46),
            ),
            photos = listOf(
                HandoverPhoto("Front · at pickup", at(d1, 11, 49), courier.name),
                HandoverPhoto("Back · at pickup", at(d1, 11, 49), courier.name),
                HandoverPhoto("Sealed bag", at(d1, 11, 51), courier.name),
            ),
            audit = listOf(
                AuditEvent(at(d0, 21, 14), "Repair booked", "Pickup ${Slot(d1, 11).window}. Estimate ₹3,899 – ₹5,299.", "You", EventKind.STATUS),
                AuditEvent(at(d1, 9, 2), "Partner assigned", "${courier.name} · ${courier.vehicle}", "Switch On", EventKind.STATUS),
                AuditEvent(at(d1, 11, 47), "Identity checked at the door", "OTP 4 1 8 2 matched", courier.name, EventKind.CUSTODY),
                AuditEvent(at(d1, 11, 51), "Device sealed and picked up", "Seal 7Q4K-2190 · 3 handover photos", courier.name, EventKind.CUSTODY),
                AuditEvent(at(d1, 12, 34), "Handed to repair shop", "Seal intact on arrival · ${shop.name}", shop.name, EventKind.CUSTODY),
                AuditEvent(at(d1, 12, 36), "Seal opened on camera", "Bench 2 · ${shop.technician}", shop.name, EventKind.CUSTODY),
                AuditEvent(at(d1, 17, 40), "Diagnosis complete", "Display cracked, touch layer dead", shop.name, EventKind.STATUS),
                AuditEvent(at(d1, 17, 46), "Quote sent for approval", "₹4,700 · within your estimate", shop.name, EventKind.ALERT),
            ),
        )
    }

    /** Completed, paid, warranty still running: the one eligible for a claim. */
    private fun dell(today: LocalDate): Repair {
        val delivered = today.minusDays(37)
        val start = delivered.minusDays(3)
        val courier = couriers[2]; val shop = shops[2]
        val quote = Quote(
            diagnosis = "Battery worn to 54% health; swelling starting near the trackpad.",
            findings = listOf("Charging circuit tested OK", "SSD health 96%", "Trackpad undamaged"),
            items = listOf(
                LineItem("Replacement battery, 41 Wh", "Part", 2150, ItemKind.PART),
                LineItem("Adhesives and insulation", "Part", 149, ItemKind.PART),
                LineItem("Labour", "Battery replacement · 45 min", 600, ItemKind.LABOUR),
                LineItem("Pickup and drop", "Doorstep, both ways", 0, ItemKind.FEE),
            ),
            warranty = "90 days on battery and labour",
            sentAt = at(start.plusDays(1), 15, 20), decision = Decision.APPROVED, decidedAt = at(start.plusDays(1), 15, 41),
        )
        return Repair(
            id = "SO-471055", device = DeviceType.LAPTOP, issue = IssueType.BATTERY, brand = "Dell", model = "Inspiron 15 3520",
            symptoms = listOf("Drains quickly", "Swollen or bulging"), notes = "", photoCount = 1,
            estimateLow = 2799, estimateHigh = 3799, turnaround = "2–3 days", address = work, slot = Slot(start, 15),
            createdAt = at(start.minusDays(1), 20, 3), stage = Stage.DELIVERED, courier = courier, shop = shop, sealCode = "M2RT-8841",
            quote = quote, deliveredAt = at(delivered, 18, 22), invoiceNo = "INV/2026-27/004812",
            payment = Payment(PayMethod.UPI, 2899, at(delivered, 18, 26), "UPI ref 428713390625"),
            photos = listOf(HandoverPhoto("Lid · at pickup", at(start, 15, 34), courier.name), HandoverPhoto("Sealed bag", at(start, 15, 36), courier.name)),
            audit = listOf(
                AuditEvent(at(start.minusDays(1), 20, 3), "Repair booked", "Estimate ₹2,799 – ₹3,799", "You", EventKind.STATUS),
                AuditEvent(at(start, 15, 36), "Device sealed and picked up", "Seal M2RT-8841", courier.name, EventKind.CUSTODY),
                AuditEvent(at(start, 16, 52), "Handed to repair shop", "Seal intact on arrival", shop.name, EventKind.CUSTODY),
                AuditEvent(at(start.plusDays(1), 15, 20), "Quote sent", "₹2,899", shop.name, EventKind.ALERT),
                AuditEvent(at(start.plusDays(1), 15, 41), "Quote approved", "₹2,899", "You", EventKind.STATUS),
                AuditEvent(at(delivered, 11, 5), "Quality check passed", "Battery 100% · 6 h 40 min runtime test", shop.name, EventKind.STATUS),
                AuditEvent(at(delivered, 18, 22), "Delivered", "Signed for by Priya S.", courier.name, EventKind.CUSTODY),
                AuditEvent(at(delivered, 18, 26), "Paid by UPI", "₹2,899 · ref 428713390625", "You", EventKind.PAYMENT),
            ),
        )
    }

    /** Quote declined: returned as it was, nothing to pay. */
    private fun declinedTab(today: LocalDate): Repair {
        val start = today.minusDays(64)
        val courier = couriers[1]; val shop = shops[1]
        return Repair(
            id = "SO-468204", device = DeviceType.TABLET, issue = IssueType.WATER, brand = "Samsung", model = "Galaxy Tab S6 Lite",
            symptoms = listOf("Spilled drink", "Won't turn on"), notes = "Chai spilled on it overnight.", photoCount = 0,
            estimateLow = 2299, estimateHigh = 2999, turnaround = "3–5 days", address = home, slot = Slot(start, 9),
            createdAt = at(start.minusDays(1), 22, 40), stage = Stage.DELIVERED, courier = courier, shop = shop, sealCode = "C9LP-3307",
            quote = Quote(
                diagnosis = "Liquid reached the main board; display flex corroded.",
                findings = listOf("Board needs replacement", "Display flex corroded", "Battery tested OK"),
                items = listOf(
                    LineItem("Main board (refurbished)", "Part", 5400, ItemKind.PART),
                    LineItem("Display flex cable", "Part", 650, ItemKind.PART),
                    LineItem("Labour", "Board swap · 2 h", 900, ItemKind.LABOUR),
                    LineItem("Pickup and drop", "Doorstep, both ways", 0, ItemKind.FEE),
                ),
                warranty = "90 days on parts and labour", sentAt = at(start.plusDays(2), 13, 10),
                decision = Decision.DECLINED, decidedAt = at(start.plusDays(2), 19, 2), declineReason = "Costs more than a new tablet",
            ),
            deliveredAt = at(start.plusDays(3), 17, 15),
            audit = listOf(
                AuditEvent(at(start.minusDays(1), 22, 40), "Repair booked", "Estimate ₹2,299 – ₹2,999", "You", EventKind.STATUS),
                AuditEvent(at(start, 9, 41), "Device sealed and picked up", "Seal C9LP-3307", courier.name, EventKind.CUSTODY),
                AuditEvent(at(start.plusDays(2), 13, 10), "Quote sent", "₹6,950 · above your estimate", shop.name, EventKind.ALERT),
                AuditEvent(at(start.plusDays(2), 19, 2), "Quote declined", "Costs more than a new tablet", "You", EventKind.STATUS),
                AuditEvent(at(start.plusDays(3), 10, 5), "Resealed unrepaired", "Seal C9LP-3412", shop.name, EventKind.CUSTODY),
                AuditEvent(at(start.plusDays(3), 17, 15), "Returned to you", "Nothing to pay", courier.name, EventKind.CUSTODY),
            ),
        )
    }

    /** Completed long enough ago that the warranty has run out. */
    private fun redmi(today: LocalDate): Repair {
        val delivered = today.minusDays(141)
        val courier = couriers[0]; val shop = shops[0]
        return Repair(
            id = "SO-455320", device = DeviceType.PHONE, issue = IssueType.CHARGING, brand = "Xiaomi", model = "Redmi Note 12",
            symptoms = listOf("Loose port"), notes = "", photoCount = 0, estimateLow = 699, estimateHigh = 999, turnaround = "24–48 hours",
            address = home, slot = Slot(delivered.minusDays(1), 17), createdAt = at(delivered.minusDays(2), 13, 12),
            stage = Stage.DELIVERED, courier = courier, shop = shop, sealCode = "H4WD-0172",
            quote = Quote(
                diagnosis = "USB-C port pins worn.", findings = listOf("Battery health 91%"),
                items = listOf(
                    LineItem("USB-C charging port board", "Part", 450, ItemKind.PART),
                    LineItem("Labour", "Port replacement · 30 min", 300, ItemKind.LABOUR),
                    LineItem("Pickup and drop", "Doorstep, both ways", 0, ItemKind.FEE),
                ),
                warranty = "90 days on parts and labour", sentAt = at(delivered.minusDays(1), 20, 5),
                decision = Decision.APPROVED, decidedAt = at(delivered.minusDays(1), 20, 30),
            ),
            deliveredAt = at(delivered, 16, 48), invoiceNo = "INV/2026-27/001377",
            payment = Payment(PayMethod.CASH, 750, at(delivered, 16, 50), "Cash to ${courier.name}"),
            audit = listOf(
                AuditEvent(at(delivered.minusDays(2), 13, 12), "Repair booked", "Estimate ₹699 – ₹999", "You", EventKind.STATUS),
                AuditEvent(at(delivered.minusDays(1), 17, 22), "Device sealed and picked up", "Seal H4WD-0172", courier.name, EventKind.CUSTODY),
                AuditEvent(at(delivered.minusDays(1), 20, 30), "Quote approved", "₹750", "You", EventKind.STATUS),
                AuditEvent(at(delivered, 16, 48), "Delivered", "Signed for by Priya S.", courier.name, EventKind.CUSTODY),
                AuditEvent(at(delivered, 16, 50), "Paid in cash", "₹750 to ${courier.name}", "You", EventKind.PAYMENT),
            ),
        )
    }

    private fun cancelledBuds(today: LocalDate): Repair {
        val d = today.minusDays(77)
        return Repair(
            id = "SO-463781", device = DeviceType.AUDIO, issue = IssueType.BATTERY, brand = "boAt", model = "Airdopes 141",
            symptoms = listOf("Drains quickly"), notes = "", photoCount = 0, estimateLow = 699, estimateHigh = 999, turnaround = "2–4 days",
            address = home, slot = Slot(d.plusDays(1), 13), createdAt = at(d, 10, 4), stage = Stage.PICKUP_SCHEDULED,
            cancellation = Cancellation(at(d, 10, 31), "Bought a replacement instead"),
            audit = listOf(
                AuditEvent(at(d, 10, 4), "Repair booked", "Estimate ₹699 – ₹999", "You", EventKind.STATUS),
                AuditEvent(at(d, 10, 31), "Cancelled before pickup", "Bought a replacement instead · no charge", "You", EventKind.STATUS),
            ),
        )
    }
}
