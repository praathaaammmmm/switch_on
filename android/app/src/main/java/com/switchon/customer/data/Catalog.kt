package com.switchon.customer.data

import com.switchon.customer.data.DeviceType.*
import com.switchon.customer.data.IssueType.*
import kotlin.math.ceil

/**
 * Published base prices (INR, GST included). A null price means the issue doesn't apply to that device.
 * Starts with the bundled price list; in the prod build [apply] replaces it with the server's catalogue at session
 * start, so what the app shows always matches what the server will charge. The server recomputes every estimate
 * itself and never accepts prices from the app.
 */
object Catalog {

    @Volatile private var base: Map<DeviceType, Map<IssueType, Int?>> = mapOf(
        PHONE to mapOf(SCREEN to 1499, BATTERY to 899, CHARGING to 699, WATER to 1199, PERFORMANCE to 599, OTHER to 399),
        LAPTOP to mapOf(SCREEN to 3999, BATTERY to 2499, CHARGING to 1299, WATER to 2499, PERFORMANCE to 999, OTHER to 499),
        TABLET to mapOf(SCREEN to 2999, BATTERY to 1799, CHARGING to 899, WATER to 1699, PERFORMANCE to 799, OTHER to 399),
        PC to mapOf(SCREEN to 2499, BATTERY to 499, CHARGING to 1499, WATER to 1999, PERFORMANCE to 999, OTHER to 499),
        WEARABLE to mapOf(SCREEN to 1999, BATTERY to 1299, CHARGING to 699, WATER to 1299, PERFORMANCE to null, OTHER to 399),
        AUDIO to mapOf(SCREEN to null, BATTERY to 999, CHARGING to 599, WATER to 899, PERFORMANCE to null, OTHER to 349),
    )

    /** Shown in place of the generic issue label where the device makes it read oddly. */
    fun issueLabel(device: DeviceType, issue: IssueType): String = labelOverrides["${device.name}/${issue.name}"] ?: when {
        device == PC && issue == SCREEN -> "Monitor"
        device == PC && issue == BATTERY -> "CMOS / UPS battery"
        device == PC && issue == CHARGING -> "Power supply"
        device == AUDIO && issue == BATTERY -> "Battery / case"
        device == LAPTOP && issue == PERFORMANCE -> "Speed-up"
        else -> issue.label
    }

    fun basePrice(device: DeviceType, issue: IssueType): Int? = base[device]?.get(issue)

    /** The lowest published price for [device], for "from ₹…" on the device categories. */
    fun fromPrice(device: DeviceType): Int? = base[device]?.values?.filterNotNull()?.minOrNull()

    @Volatile var brands: Map<DeviceType, List<Pair<String, Double>>> = mapOf(
        PHONE to listOf("Apple" to 2.6, "Samsung" to 1.5, "Google" to 1.6, "OnePlus" to 1.3, "Xiaomi" to 1.0, "Vivo" to 1.0, "Oppo" to 1.0, "Realme" to 0.95),
        LAPTOP to listOf("Apple" to 2.2, "Dell" to 1.1, "HP" to 1.1, "Lenovo" to 1.05, "Asus" to 1.0, "Acer" to 0.95),
        TABLET to listOf("Apple" to 2.0, "Samsung" to 1.3, "Lenovo" to 1.0, "Xiaomi" to 1.0),
        PC to listOf("Custom build" to 1.0, "Dell" to 1.0, "HP" to 1.0, "Lenovo" to 1.0),
        WEARABLE to listOf("Apple" to 2.2, "Samsung" to 1.4, "Fitbit" to 1.2, "Noise" to 0.8, "boAt" to 0.8),
        AUDIO to listOf("Apple" to 1.8, "Bose" to 1.5, "Sony" to 1.3, "JBL" to 1.0, "boAt" to 0.7),
    )

    @Volatile var models: Map<String, List<String>> = mapOf(
        "PHONE/Apple" to listOf("iPhone 15", "iPhone 14", "iPhone 13", "iPhone 12", "iPhone 11"),
        "PHONE/Samsung" to listOf("Galaxy S23", "Galaxy A54", "Galaxy M34", "Galaxy F15"),
        "PHONE/Google" to listOf("Pixel 8", "Pixel 7a", "Pixel 7"),
        "PHONE/OnePlus" to listOf("OnePlus 12R", "OnePlus 11", "Nord CE 3"),
        "PHONE/Xiaomi" to listOf("Redmi Note 13", "Redmi Note 12", "Redmi 13C"),
        "PHONE/Vivo" to listOf("V29", "T2", "Y56"),
        "PHONE/Oppo" to listOf("Reno 10", "A78", "F25 Pro"),
        "PHONE/Realme" to listOf("Narzo 60", "Realme 12 Pro", "C55"),
        "LAPTOP/Apple" to listOf("MacBook Air M1", "MacBook Air M2", "MacBook Pro 14\""),
        "LAPTOP/Dell" to listOf("Inspiron 15 3520", "Vostro 3420", "XPS 13"),
        "LAPTOP/HP" to listOf("Pavilion 15", "Victus 15", "EliteBook 840"),
        "LAPTOP/Lenovo" to listOf("IdeaPad Slim 3", "ThinkPad E14", "Legion 5"),
        "LAPTOP/Asus" to listOf("VivoBook 15", "TUF A15", "ZenBook 14"),
        "LAPTOP/Acer" to listOf("Aspire 7", "Aspire Lite", "Nitro V"),
        "TABLET/Apple" to listOf("iPad 10th gen", "iPad Air M1", "iPad mini 6"),
        "TABLET/Samsung" to listOf("Galaxy Tab S6 Lite", "Galaxy Tab A8", "Galaxy Tab S9 FE"),
        "WEARABLE/Apple" to listOf("Watch SE", "Watch Series 8", "Watch Series 9"),
        "AUDIO/Apple" to listOf("AirPods Pro 2", "AirPods 3"),
        "AUDIO/boAt" to listOf("Airdopes 141", "Rockerz 450"),
    )

    fun modelsFor(device: DeviceType, brand: String?): List<String> = models["${device.name}/$brand"].orEmpty()

    private fun multiplier(device: DeviceType, brand: String?) =
        brands[device]?.firstOrNull { it.first == brand }?.second

    /** Ends prices in 99, the way the published price list does. */
    private fun round99(x: Double) = (ceil(x / 100.0) * 100).toInt() - 1

    /**
     * Low and high end of the base estimate. Without a brand we can only quote the "from" price and a wide ceiling;
     * picking the brand narrows it.
     */
    fun estimate(device: DeviceType, issue: IssueType, brand: String?): Pair<Int, Int>? {
        val b = basePrice(device, issue) ?: return null
        val m = multiplier(device, brand)
        return if (m == null) b to round99(b * 2.2) else round99(b * m) to round99(b * m * 1.35)
    }

    fun turnaround(device: DeviceType, issue: IssueType): String = turnarounds["${device.name}/${issue.name}"] ?: when {
        issue == WATER -> "3–5 days"
        device == PHONE && issue in setOf(SCREEN, BATTERY, CHARGING) -> "24–48 hours"
        device == PHONE -> "1–2 days"
        device == LAPTOP && issue == PERFORMANCE -> "1–2 days"
        device == LAPTOP || device == PC -> "2–3 days"
        else -> "2–4 days"
    }

    fun included(issue: IssueType): List<String> = buildList {
        add("Free doorstep pickup and drop")
        add("Full diagnosis by a verified repair shop")
        when (issue) {
            SCREEN -> add("Display part, fitting and new adhesive seal")
            BATTERY -> add("New battery, fitting and health report")
            CHARGING -> add("Port or board-level charging fix")
            WATER -> add("Ultrasonic cleaning and corrosion treatment")
            PERFORMANCE -> add("Clean-up, thermal paste and OS optimisation")
            OTHER -> add("Fault-finding across power, display and boards")
        }
        add("Tamper-sealed bag from pickup to return")
        add("90-day warranty on parts and labour")
    }

    fun symptoms(issue: IssueType): List<String> = when (issue) {
        SCREEN -> listOf("Cracked glass", "Lines or patches", "Touch not working", "Black screen", "Flickering")
        BATTERY -> listOf("Drains quickly", "Swollen or bulging", "Shuts off randomly", "Gets hot", "Won't hold charge")
        CHARGING -> listOf("Won't charge", "Loose port", "Charges slowly", "Only charges at an angle", "Not detected by PC")
        WATER -> listOf("Dropped in water", "Spilled drink", "Rain exposure", "Won't turn on", "Speaker muffled")
        PERFORMANCE -> listOf("Very slow", "Hangs or freezes", "Overheating", "Loud fan", "Storage full")
        OTHER -> listOf("No sound", "Camera fault", "Buttons stuck", "Wi-Fi / Bluetooth", "Won't turn on")
    }

    /** Builds the shop's itemised quote for a mock diagnosis, landing inside the customer's estimate. */
    fun mockQuote(device: DeviceType, issue: IssueType, low: Int, high: Int, at: java.time.LocalDateTime): Quote {
        val target = (low + high) / 2
        val labour = ((target * 0.24) / 50).toInt() * 50
        val consumables = when (issue) { SCREEN, WATER -> 249; else -> 149 }
        val part = ((target - labour - consumables) / 50) * 50
        val (diagnosis, findings, partName) = when (issue) {
            SCREEN -> Triple("Display panel cracked; touch layer unresponsive along the lower edge.",
                listOf("Cameras, speakers and sensors tested OK", "No liquid indicators tripped", "Frame within tolerance"),
                "Display assembly (premium grade)")
            BATTERY -> Triple("Battery health at 71% with heavy voltage sag under load.",
                listOf("No swelling against the frame", "Charging circuit tested OK", "Board shows no heat damage"),
                "Replacement battery (rated capacity)")
            CHARGING -> Triple("Charging port worn; pins 3 and 7 intermittent.",
                listOf("Battery health 88%, no action needed", "Charging IC tested OK", "Cable and adapter not at fault"),
                "Charging port flex")
            WATER -> Triple("Corrosion on the power-management area after liquid exposure.",
                listOf("Display and battery survived", "Speaker mesh cleaned", "Data partition intact"),
                "Power IC and corrosion treatment")
            PERFORMANCE -> Triple("Storage nearly full and fan clogged; CPU throttling at 92°C.",
                listOf("No hardware faults found", "RAM and storage pass tests", "Thermal paste dried out"),
                "Thermal paste and fan service kit")
            OTHER -> Triple("Loose internal connector after an earlier drop.",
                listOf("Board tested OK after reseating", "No liquid damage", "All buttons and ports working"),
                "Connector and fastening kit")
        }
        return Quote(
            diagnosis = diagnosis,
            findings = findings,
            items = listOf(
                LineItem(partName, "Part", part, ItemKind.PART),
                LineItem("Adhesives, seals and consumables", "Part", consumables, ItemKind.PART),
                LineItem("Labour", "${issueLabel(device, issue)} repair · bench time", labour, ItemKind.LABOUR),
                LineItem("Pickup and drop", "Doorstep, both ways", 0, ItemKind.FEE),
            ),
            warranty = "90 days on parts and labour",
            sentAt = at,
        )
    }

    /** Pincode prefixes we serve today: Bengaluru, Mumbai, Delhi NCR, Pune, Hyderabad. */
    @Volatile private var servedPrefixes = listOf("560", "400", "110", "122", "201", "411", "500")

    @Volatile private var turnarounds: Map<String, String> = emptyMap()
    @Volatile private var labelOverrides: Map<String, String> = emptyMap()

    /** One offering from the server: device, issue, base price, turnaround and display label. */
    data class Offering(val device: String, val issue: String, val basePrice: Int, val turnaround: String, val label: String)

    private val bundled by lazy { listOf(base, brands, models, servedPrefixes) }

    /** Back to the bundled price list (used by tests, and if the server catalogue must be discarded). */
    @Suppress("UNCHECKED_CAST")
    fun reset() {
        val (b, br, m, p) = bundled
        base = b as Map<DeviceType, Map<IssueType, Int?>>
        brands = br as Map<DeviceType, List<Pair<String, Double>>>
        models = m as Map<String, List<String>>
        servedPrefixes = p as List<String>
        turnarounds = emptyMap()
        labelOverrides = emptyMap()
    }

    /** Replaces the bundled catalogue with the server's. Unknown device or issue codes are ignored. */
    fun apply(brandsByDevice: Map<String, List<Triple<String, Double, List<String>>>>, offerings: List<Offering>, prefixes: List<String>) {
        bundled // capture the bundled values before the first replacement
        val devices = DeviceType.entries.associateBy { it.name }
        val issues = IssueType.entries.associateBy { it.name }
        if (offerings.isNotEmpty()) {
            base = DeviceType.entries.associateWith { d ->
                IssueType.entries.associateWith { i -> offerings.firstOrNull { it.device == d.name && it.issue == i.name }?.basePrice }
            }
            turnarounds = offerings.associate { "${it.device}/${it.issue}" to it.turnaround }
            labelOverrides = offerings.filter { o -> issues[o.issue]?.label != o.label }.associate { "${it.device}/${it.issue}" to it.label }
        }
        if (brandsByDevice.isNotEmpty()) {
            brands = brandsByDevice.mapNotNull { (code, list) -> devices[code]?.let { it to list.map { b -> b.first to b.second } } }.toMap()
            models = brandsByDevice.flatMap { (code, list) -> list.filter { it.third.isNotEmpty() }.map { "$code/${it.first}" to it.third } }.toMap()
        }
        if (prefixes.isNotEmpty()) servedPrefixes = prefixes
    }

    fun isServiceable(pincode: String) = pincode.length == 6 && servedPrefixes.any { pincode.startsWith(it) }
}
