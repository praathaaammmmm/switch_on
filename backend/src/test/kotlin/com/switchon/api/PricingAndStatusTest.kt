package com.switchon.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PricingTest {
    @Test fun `estimates end in 99 and widen without a brand`() {
        assertEquals(1499 to 3299, Pricing.estimate(1499, null))
        assertEquals(3899 to 5299, Pricing.estimate(1499, 2.6))   // iPhone screen
        assertEquals(2799 to 3799, Pricing.estimate(2499, 1.1))   // Dell battery (2748.9 rounds up)
        assertEquals(699 to 999, Pricing.estimate(999, 0.7))      // boAt earbuds battery (699.3 rounds to 699)
    }

    @Test fun `gst split adds back up to the total`() {
        for (total in listOf(750, 2899, 4700, 1, 99999)) {
            val (taxable, cgst, sgst) = Pricing.gstSplit(total)
            assertEquals(total, taxable + cgst + sgst)
            assertTrue(cgst - sgst in -1..1)
        }
        assertEquals(Triple(3983, 358, 359), Pricing.gstSplit(4700))
    }

    @Test fun `mock quote lands inside the estimate`() {
        val (lines, _) = Pricing.mockQuote("BATTERY", "Battery", 2699, 3599)
        val total = lines.sumOf { it.amount }
        assertTrue(total in 2699..3599, "total $total")
        assertTrue(lines.all { it.amount >= 0 })
    }
}

class StatusTest {
    @Test fun `happy path is a chain of allowed transitions`() {
        val path = listOf("PICKUP_REQUESTED", "PICKUP_SCHEDULED", "PARTNER_ASSIGNED", "PICKED_UP", "AT_SHOP", "DIAGNOSED", "QUOTE_PENDING",
            "REPAIRING", "QUALITY_CHECK", "RETURN_SCHEDULED", "DELIVERED", "PAYMENT_PENDING", "PAYMENT_COMPLETED", "WARRANTY_ACTIVE").map(RepairStatus::valueOf)
        assertTrue(canTransition(RepairStatus.DRAFT, path.first()))
        path.zipWithNext().forEach { (a, b) -> assertTrue(canTransition(a, b), "$a → $b") }
        assertTrue(RepairStatus.WARRANTY_ACTIVE.isTerminal)
    }

    @Test fun `cancellation is only possible before pickup`() {
        RepairStatus.entries.forEach { s ->
            assertEquals(s.beforePickup && s != RepairStatus.CANCELLED, canTransition(s, RepairStatus.CANCELLED), s.name)
        }
        assertFalse(RepairStatus.PICKED_UP.beforePickup)
    }

    @Test fun `no skipping steps or going backwards`() {
        assertFalse(canTransition(RepairStatus.PICKUP_SCHEDULED, RepairStatus.AT_SHOP))
        assertFalse(canTransition(RepairStatus.QUOTE_PENDING, RepairStatus.DELIVERED))
        assertFalse(canTransition(RepairStatus.DELIVERED, RepairStatus.REPAIRING))
        assertFalse(canTransition(RepairStatus.CANCELLED, RepairStatus.PICKUP_SCHEDULED))
        assertTrue(RepairStatus.CANCELLED.isTerminal)
    }

    @Test fun `unrepaired paths return the device`() {
        assertTrue(canTransition(RepairStatus.QUOTE_PENDING, RepairStatus.QUOTE_DECLINED))
        assertTrue(canTransition(RepairStatus.QUOTE_DECLINED, RepairStatus.RETURN_SCHEDULED))
        assertTrue(canTransition(RepairStatus.DIAGNOSED, RepairStatus.REPAIR_NOT_POSSIBLE))
        assertTrue(canTransition(RepairStatus.REPAIR_NOT_POSSIBLE, RepairStatus.RETURN_SCHEDULED))
        assertTrue(canTransition(RepairStatus.PICKUP_FAILED, RepairStatus.PICKUP_SCHEDULED))
    }
}
