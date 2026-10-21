package com.switchon.customer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationTest {
    @Test fun mobile() {
        assertNull(Validation.mobile("9845012873"))
        assertNull(Validation.mobile("6000000000"))
        assertNotNull(Validation.mobile(""))
        assertNotNull(Validation.mobile("984501287"))
        assertNotNull(Validation.mobile("5845012873"))
        assertNotNull(Validation.mobile("98450a2873"))
    }

    @Test fun pincode() {
        assertNull(Validation.pincode("560038"))
        assertNull(Validation.pincode("400001"))
        assertEquals("Pincodes have 6 digits.", Validation.pincode("5600"))
        assertNotNull(Validation.pincode("060038"))
        assertNotNull("not served yet", Validation.pincode("682001"))
    }

    @Test fun upiId() {
        assertNull(Validation.upiId("priya@okhdfcbank"))
        assertNull(Validation.upiId("priya.sharma-1@ybl"))
        assertNotNull(Validation.upiId(""))
        assertNotNull(Validation.upiId("priya"))
        assertNotNull(Validation.upiId("@okaxis"))
        assertNotNull(Validation.upiId("priya@"))
    }

    @Test fun notesAndName() {
        assertNull(Validation.notes("x".repeat(Validation.NOTES_MAX)))
        assertNotNull(Validation.notes("x".repeat(Validation.NOTES_MAX + 1)))
        assertNull(Validation.name("Priya"))
        assertNotNull(Validation.name("P"))
        assertNotNull(Validation.name("   "))
    }

    @Test fun mobileFormatting() {
        assertEquals("+91 98450 12873", formatMobile("9845012873"))
        assertEquals("98450", formatMobile("98450"))
    }
}
