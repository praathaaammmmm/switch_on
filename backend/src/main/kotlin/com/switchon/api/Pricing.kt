package com.switchon.api

import kotlin.math.ceil

/** Server-side estimate rules. The app shows these numbers; it never sends prices back. */
object Pricing {
    /** Ends prices in 99, the way the published price list does. */
    fun round99(x: Double) = (ceil(x / 100.0) * 100).toInt() - 1

    /** Without a brand we quote the "from" price and a wide ceiling; a known brand narrows it. */
    fun estimate(base: Int, multiplier: Double?): Pair<Int, Int> =
        if (multiplier == null) base to round99(base * 2.2) else round99(base * multiplier) to round99(base * multiplier * 1.35)

    data class Line(val label: String, val detail: String, val kind: String, val amount: Int)

    /** GST-inclusive split for the invoice: 18% GST as 9% CGST + 9% SGST. */
    fun gstSplit(total: Int): Triple<Int, Int, Int> {
        val taxable = Math.round(total / 1.18).toInt()
        val gst = total - taxable
        return Triple(taxable, gst / 2, gst - gst / 2)
    }

    /** The shop's itemised quote for a diagnosis, landing inside the estimate (used by the dev tools and seed). */
    fun mockQuote(issue: String, issueLabel: String, low: Int, high: Int): Pair<List<Line>, Diagnosis> {
        val target = (low + high) / 2
        val labour = ((target * 0.24) / 50).toInt() * 50
        val consumables = if (issue == "SCREEN" || issue == "WATER") 249 else 149
        val part = ((target - labour - consumables) / 50) * 50
        val d = when (issue) {
            "SCREEN" -> Diagnosis("Display panel cracked; touch layer unresponsive along the lower edge.",
                listOf("Cameras, speakers and sensors tested OK", "No liquid indicators tripped", "Frame within tolerance"), "Display assembly (premium grade)")
            "BATTERY" -> Diagnosis("Battery health at 71% with heavy voltage sag under load.",
                listOf("No swelling against the frame", "Charging circuit tested OK", "Board shows no heat damage"), "Replacement battery (rated capacity)")
            "CHARGING" -> Diagnosis("Charging port worn; pins 3 and 7 intermittent.",
                listOf("Battery health 88%, no action needed", "Charging IC tested OK", "Cable and adapter not at fault"), "Charging port flex")
            "WATER" -> Diagnosis("Corrosion on the power-management area after liquid exposure.",
                listOf("Display and battery survived", "Speaker mesh cleaned", "Data partition intact"), "Power IC and corrosion treatment")
            "PERFORMANCE" -> Diagnosis("Storage nearly full and fan clogged; CPU throttling at 92°C.",
                listOf("No hardware faults found", "RAM and storage pass tests", "Thermal paste dried out"), "Thermal paste and fan service kit")
            else -> Diagnosis("Loose internal connector after an earlier drop.",
                listOf("Board tested OK after reseating", "No liquid damage", "All buttons and ports working"), "Connector and fastening kit")
        }
        return listOf(
            Line(d.partName, "Part", "PART", part),
            Line("Adhesives, seals and consumables", "Part", "PART", consumables),
            Line("Labour", "$issueLabel repair · bench time", "LABOUR", labour),
            Line("Pickup and drop", "Doorstep, both ways", "FEE", 0),
        ) to d
    }

    data class Diagnosis(val summary: String, val findings: List<String>, val partName: String)
}
