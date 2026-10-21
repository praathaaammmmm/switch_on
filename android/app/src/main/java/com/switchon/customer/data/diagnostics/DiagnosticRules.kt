package com.switchon.customer.data.diagnostics

import com.switchon.customer.data.IssueType
import com.switchon.customer.data.diagnostics.DiagnosticStatus.*
import com.switchon.customer.data.diagnostics.DiagnosticTest.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns raw readings and customer answers into results, findings, a summary and a confidence level.
 * Pure Kotlin, so every rule is unit-tested. Wording rules: say "possible" and "may", never claim a part is broken,
 * and treat anything the phone doesn't expose as unsupported, not failed.
 */
object DiagnosticRules {

    const val WARM_BATTERY_C = 40f
    const val HOT_BATTERY_C = 45f
    const val LOW_STORAGE_FRACTION = 0.10
    const val LOW_STORAGE_BYTES = 2L * 1024 * 1024 * 1024
    /** Below this share of memory available, or when Android itself flags low memory, the check warns. */
    const val LOW_MEMORY_FRACTION = 0.10

    // ---- Device information (never a repair result) ----

    fun deviceInfo(d: DeviceInfo, s: SoftwareSnapshot? = null) = DiagnosticResult(
        DEVICE_INFO, INFO, "${d.manufacturer} ${d.model} · device information, not a repair result",
        listOf(
            DiagnosticMetric("Manufacturer", d.manufacturer),
            DiagnosticMetric("Model", d.model),
            DiagnosticMetric("Android", "${d.androidVersion} · API ${d.sdkInt}"),
            DiagnosticMetric.of("Security patch", s?.securityPatch?.let(::patchDate)),
            DiagnosticMetric.of("Build type", s?.buildType?.let(::buildType)),
            DiagnosticMetric.of("Uptime", s?.uptimeMs?.takeIf { it > 0 }?.let(::duration)),
        ),
    )

    fun display(s: DisplaySnapshot?): DiagnosticResult {
        if (s == null) return DiagnosticResult(DISPLAY_INFO, INFO, "Display details aren't available on this device")
        val refresh = s.refreshHz?.let { now ->
            val max = s.maxRefreshHz?.takeIf { it > now + 1 }
            "${hz(now)}" + (max?.let { " (supports up to ${hz(it)})" } ?: "")
        }
        val brightness = s.brightnessPercent?.let { pct ->
            "$pct%" + when (s.adaptiveBrightness) { true -> " · adaptive on"; false -> " · adaptive off"; null -> "" }
        }
        return DiagnosticResult(
            DISPLAY_INFO, INFO, "Display settings as reported. These can't show screen damage; the colour test checks that with your eyes.",
            listOf(
                DiagnosticMetric.of("Resolution", if (s.widthPx != null && s.heightPx != null) "${s.widthPx} × ${s.heightPx} px" else null),
                DiagnosticMetric.of("Density", s.densityDpi?.let { "$it dpi" }),
                DiagnosticMetric.of("Refresh rate", refresh),
                DiagnosticMetric.of("Brightness", brightness),
            ),
        )
    }

    fun cameraInfo(cameras: List<CameraDetail>): DiagnosticResult {
        if (cameras.isEmpty()) return DiagnosticResult(CAMERA_INFO, INFO, "No cameras are reported on this device")
        val metrics = cameras.sortedBy { it.facing.ordinal }.map { c ->
            val parts = listOfNotNull(
                when (c.flash) { true -> "Flash"; false -> "No flash"; null -> null },
                c.hardwareLevel?.let { "$it camera support" },
                if (c.maxPreviewWidth != null && c.maxPreviewHeight != null) "Preview up to ${c.maxPreviewWidth} × ${c.maxPreviewHeight}" else null,
            )
            DiagnosticMetric.of("${c.facing.label} camera", parts.takeIf { it.isNotEmpty() }?.joinToString(" · "))
        }
        val names = cameras.map { it.facing }.distinct().sortedBy { it.ordinal }.joinToString(" and ") { it.label.lowercase() }
        return DiagnosticResult(CAMERA_INFO, INFO, "${cameras.size} ${if (cameras.size == 1) "camera" else "cameras"} reported ($names). The camera check below is what shows whether ${if (cameras.size == 1) "it works" else "they work"}.", metrics)
    }

    fun audioInfo(a: AudioSnapshot?): DiagnosticResult {
        if (a == null) return DiagnosticResult(AUDIO_INFO, INFO, "Audio details aren't available on this device")
        return DiagnosticResult(
            AUDIO_INFO, INFO, "Audio hardware as reported. Your answers in the speaker and microphone checks are the actual result.",
            listOf(
                DiagnosticMetric.of("Sound output now", a.route?.label),
                DiagnosticMetric.of("Built-in speaker", a.builtInSpeaker?.let { if (it) "Reported" else "Not reported" }),
                DiagnosticMetric.of("Microphone", a.builtInMicrophone?.let { if (it) "Reported" else "Not reported" }),
                DiagnosticMetric.of("Media volume", a.mediaVolumePercent?.let { if (it == 0) "Muted" else "$it%" }),
            ),
        )
    }

    /** Things that would make the speaker check misleading if nobody mentioned them. */
    fun audioFindings(a: AudioSnapshot?): List<DiagnosticFinding> = buildList {
        if (a == null) return@buildList
        if (a.route != null && a.route != AudioRoute.SPEAKER) add(DiagnosticFinding(AUDIO_INFO, FindingLevel.INFO,
            "Sound was going to ${a.route.label.lowercase()} during the check, so the speaker check may not reflect the built-in speaker."))
        if (a.mediaVolumePercent == 0) add(DiagnosticFinding(AUDIO_INFO, FindingLevel.INFO,
            "Media volume was muted. Turn it up before the speaker check, or the tone won't play."))
    }

    /** A hint for the speaker step when the tone wouldn't come out of the built-in speaker. Null when all is set. */
    fun speakerHint(a: AudioSnapshot?): String? = when {
        a == null -> null
        a.mediaVolumePercent == 0 -> "Media volume is muted. Turn it up first, or you won't hear the tone."
        a.route != null && a.route != AudioRoute.SPEAKER -> "Sound is going to ${a.route.label.lowercase()}. Disconnect it to check the built-in speaker."
        else -> null
    }

    // ---- Automatic checks ----

    fun memory(m: MemorySnapshot?): DiagnosticResult {
        if (m == null || m.totalBytes <= 0) return DiagnosticResult(MEMORY, UNSUPPORTED, "Memory details aren't available on this device")
        val fraction = m.availableBytes.toDouble() / m.totalBytes
        val metrics = listOf(
            DiagnosticMetric("Available", gigabytes(m.availableBytes)),
            DiagnosticMetric("Total", gigabytes(m.totalBytes)),
            DiagnosticMetric("Available share", "${(fraction * 100).roundToInt()}%"),
            DiagnosticMetric("Android low-memory state", if (m.systemLowMemory) "Yes" else "No"),
        )
        return if (memoryLow(m)) DiagnosticResult(MEMORY, ATTENTION, "Memory was very low during the check (${gigabytes(m.availableBytes)} available)", metrics)
        else DiagnosticResult(MEMORY, PASS, "${gigabytes(m.availableBytes)} available of ${gigabytes(m.totalBytes)}", metrics)
    }

    private fun memoryLow(m: MemorySnapshot) = m.totalBytes > 0 && (m.systemLowMemory || m.availableBytes.toDouble() / m.totalBytes < LOW_MEMORY_FRACTION)

    fun memoryFindings(m: MemorySnapshot?): List<DiagnosticFinding> =
        if (m != null && memoryLow(m)) listOf(DiagnosticFinding(MEMORY, FindingLevel.WARNING,
            "Memory was very low during the check. Apps may be slow or close in the background; closing apps or restarting may help. On its own this doesn't point to a hardware problem."))
        else emptyList()

    fun battery(b: BatterySnapshot?): DiagnosticResult {
        if (b == null) return DiagnosticResult(BATTERY, UNSUPPORTED, "Battery details aren't available on this device")
        val metrics = listOf(
            DiagnosticMetric.of("Charge", b.levelPercent?.let { "$it%" }),
            DiagnosticMetric("State", b.state.label),
            DiagnosticMetric("Power source", b.source.label),
            DiagnosticMetric.of("Temperature", b.temperatureC?.let(::celsius)),
            DiagnosticMetric.of("Voltage", b.voltageMv?.takeIf { it > 0 }?.let { String.format(Locale.ENGLISH, "%.2f V", it / 1000.0) }),
            DiagnosticMetric("Reported health", b.health.label),
            DiagnosticMetric.of("Charge cycles", b.cycleCount?.takeIf { it >= 0 }?.toString()),
            DiagnosticMetric.of("Charge remaining", b.chargeCounterMah?.takeIf { it > 0 }?.let { "$it mAh (as reported)" }),
            DiagnosticMetric.of("Current now", b.currentNowMa?.let { "$it mA" }),
            DiagnosticMetric.of("Average current", b.currentAverageMa?.let { "$it mA" }),
        )
        val hot = (b.temperatureC ?: 0f) >= HOT_BATTERY_C
        val status = if (hot || b.health.concerning) ATTENTION else PASS
        val note = when {
            b.health.concerning -> "Android reports battery health as \"${b.health.label.lowercase()}\""
            hot -> "Battery is hot (${celsius(b.temperatureC!!)})"
            else -> "Battery readings look normal" + (b.levelPercent?.let { " · $it%" } ?: "")
        }
        return DiagnosticResult(BATTERY, status, note, metrics)
    }

    fun batteryFindings(b: BatterySnapshot?): List<DiagnosticFinding> = buildList {
        if (b == null) return@buildList
        val t = b.temperatureC
        if (t != null && t >= HOT_BATTERY_C) add(DiagnosticFinding(BATTERY, FindingLevel.RED_FLAG,
            "The battery is hot (${celsius(t)}). Unplug it and let it cool. Heat may indicate a battery problem that needs physical inspection."))
        else if (t != null && t >= WARM_BATTERY_C) add(DiagnosticFinding(BATTERY, FindingLevel.WARNING,
            "The battery is warm (${celsius(t)}). That's often just use or charging, but mention it if it happens a lot."))
        if (b.health.concerning) add(DiagnosticFinding(BATTERY, FindingLevel.RED_FLAG,
            "Android reports the battery health as \"${b.health.label.lowercase()}\". This may indicate a battery fault; the shop will confirm it."))
    }

    private fun storageLow(s: StorageSnapshot) = s.totalBytes > 0 && (s.freeBytes.toDouble() / s.totalBytes < LOW_STORAGE_FRACTION || s.freeBytes < LOW_STORAGE_BYTES)

    fun storage(s: StorageSnapshot?): DiagnosticResult {
        if (s == null || s.totalBytes <= 0) return DiagnosticResult(STORAGE, UNSUPPORTED, "Storage details aren't available on this device")
        val fraction = s.freeBytes.toDouble() / s.totalBytes
        val low = storageLow(s)
        val metrics = listOf(
            DiagnosticMetric("Free", gigabytes(s.freeBytes)),
            DiagnosticMetric("Total", gigabytes(s.totalBytes)),
            DiagnosticMetric("Free share", "${(fraction * 100).roundToInt()}%"),
        )
        return DiagnosticResult(STORAGE, if (low) ATTENTION else PASS,
            if (low) "Storage is nearly full (${gigabytes(s.freeBytes)} free)" else "${gigabytes(s.freeBytes)} free of ${gigabytes(s.totalBytes)}", metrics)
    }

    fun storageFindings(s: StorageSnapshot?): List<DiagnosticFinding> =
        if (s != null && storageLow(s))
            listOf(DiagnosticFinding(STORAGE, FindingLevel.WARNING, "Storage is nearly full (under 10% or 2 GB free). That can make a phone slow or unstable; freeing space may help before any repair."))
        else emptyList()

    fun sensors(present: Map<SensorKind, Boolean>): DiagnosticResult {
        val found = present.filterValues { it }.keys
        if (found.isEmpty()) return DiagnosticResult(SENSORS, UNSUPPORTED, "No motion or light sensors are reported")
        val metrics = SensorKind.entries.map { DiagnosticMetric(it.label, if (present[it] == true) "Detected" else "Not available on this model") }
        return DiagnosticResult(SENSORS, PASS, "${found.size} of ${SensorKind.entries.size} sensors detected", metrics)
    }

    fun sensorResponse(present: Map<SensorKind, Boolean>, responded: Map<SensorKind, Boolean>): DiagnosticResult {
        val tested = present.filterValues { it }.keys
        if (tested.isEmpty()) return DiagnosticResult(SENSOR_RESPONSE, UNSUPPORTED, "No sensors to test on this device")
        val silent = tested.filter { responded[it] != true }
        val metrics = tested.map { DiagnosticMetric(it.label, if (responded[it] == true) "Responded" else "No response during the check") }
        return if (silent.isEmpty()) DiagnosticResult(SENSOR_RESPONSE, PASS, "All ${tested.size} sensors responded", metrics)
        else DiagnosticResult(SENSOR_RESPONSE, ATTENTION, "${silent.joinToString { it.label }} gave no response during the check", metrics)
    }

    fun connectivity(c: ConnectivitySnapshot): DiagnosticResult {
        if (!c.online) return DiagnosticResult(CONNECTIVITY, SKIPPED, "Offline right now, so the connection wasn't checked", listOf(DiagnosticMetric("Status", "Offline")))
        val metrics = buildList {
            add(DiagnosticMetric("Status", "Online"))
            add(DiagnosticMetric.of("Network", c.transport?.label))
            c.validated?.let { add(DiagnosticMetric("Internet", if (it) "Reachable" else "Not confirmed by Android")) }
            if (c.transport == Transport.WIFI) add(DiagnosticMetric.of("Wi-Fi signal", c.wifiRssiDbm?.let { "${wifiBand(it)} · $it dBm" }))
        }
        return DiagnosticResult(CONNECTIVITY, PASS, "Online" + (c.transport?.let { " via ${it.label}" } ?: ""), metrics)
    }

    /** Signal where the phone is now, which says as much about the location as the phone. */
    fun wifiBand(rssi: Int) = when {
        rssi >= -55 -> "Strong"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        else -> "Weak"
    }

    /** The connection snapshot never proves the radios are fault-free; say so once. */
    val connectivityFinding = DiagnosticFinding(CONNECTIVITY, FindingLevel.INFO,
        "The connection check only shows whether the phone is online now. It doesn't test the Wi-Fi, mobile, Bluetooth, SIM or antenna hardware.")

    fun charging(samples: List<BatterySnapshot>): DiagnosticResult {
        if (samples.isEmpty()) return DiagnosticResult(CHARGING, UNSUPPORTED, "Unable to verify charging on this device")
        val detected = samples.any { it.chargingDetected }
        val currents = samples.mapNotNull { it.currentNowMa ?: it.currentAverageMa }.filter { it > 0 }
        val source = samples.lastOrNull { it.source !in setOf(PowerSource.NONE, PowerSource.UNKNOWN) }?.source
        val metrics = listOf(
            DiagnosticMetric("Charging", if (detected) "Detected" else "Not detected"),
            DiagnosticMetric.of("Power source", source?.label),
            DiagnosticMetric.of("Charging current", currents.takeIf { it.isNotEmpty() }?.let { "about ${it.average().roundToInt()} mA" }),
        )
        return if (detected) DiagnosticResult(CHARGING, PASS, "Charging detected", metrics, userConfirmed = true)
        else DiagnosticResult(CHARGING, ATTENTION, "Charging not detected", metrics, userConfirmed = true)
    }

    // ---- Guided checks (the customer's answer is the result) ----

    fun confirmed(test: DiagnosticTest, ok: Boolean, note: String, metrics: List<DiagnosticMetric> = emptyList()) =
        DiagnosticResult(test, if (ok) PASS else ATTENTION, note, metrics, userConfirmed = true)

    /** Touch grid result. The customer's answer decides the status; the grid's count goes in as a reading. */
    fun touch(ok: Boolean, hit: Int, total: Int) = confirmed(TOUCH, ok, if (ok) "Every area responded" else "Some areas did not respond", listOf(
        DiagnosticMetric("Areas responding", "$hit of $total"),
        DiagnosticMetric(AREAS_MISSED, "${(total - hit).coerceAtLeast(0)}"),
    ))

    private const val AREAS_MISSED = "Areas not responding"

    fun skipped(test: DiagnosticTest, why: String = "Skipped") = DiagnosticResult(test, SKIPPED, why)
    fun unsupported(test: DiagnosticTest, why: String) = DiagnosticResult(test, UNSUPPORTED, why)
    fun permissionDenied(test: DiagnosticTest) = DiagnosticResult(test, SKIPPED, "Permission not granted")

    /** Plain-language finding for each guided check that needs a closer look. */
    private fun guidedFinding(r: DiagnosticResult): DiagnosticFinding? = when (r.test) {
        TOUCH -> "Parts of the touchscreen didn't respond. This may indicate a screen or digitiser problem that needs physical confirmation."
        DISPLAY -> "You noticed a display issue (${r.note.lowercase()}). This may indicate a screen problem that needs physical confirmation."
        SPEAKER -> "The test tone wasn't clear. This may be the speaker, a blocked grille or a software setting."
        VIBRATION -> "You didn't feel the vibration. The vibration motor may need a closer look."
        MICROPHONE -> "The recording wasn't clear. The microphone or its opening may need a closer look."
        CAMERA -> "A camera didn't look sharp or didn't work. The lens, camera module or a setting may need a closer look."
        CHARGING -> "Charging wasn't detected while your charger was connected. This may point to the charger, cable or charging port and needs physical confirmation."
        SENSOR_RESPONSE -> "Some sensors gave no response during the short check. They may need a closer look."
        else -> null
    }?.takeIf { r.status == ATTENTION }?.let { DiagnosticFinding(r.test, FindingLevel.WARNING, it) }

    // ---- The report ----

    fun buildReport(
        device: DeviceInfo,
        results: Map<DiagnosticTest, DiagnosticResult>,
        extraFindings: List<DiagnosticFinding>,
        checkedAt: LocalDateTime,
    ): DiagnosticReport {
        val all = DiagnosticTest.entries.map { results[it] ?: if (it.info) DiagnosticResult(it, INFO, "Not read") else skipped(it, "Not run") }
        val findings = (extraFindings + all.mapNotNull(::guidedFinding)).distinctBy { it.test to it.message }
            .sortedBy { -it.level.ordinal }
        val confidence = confidence(all)
        return DiagnosticReport(
            checkedAt = checkedAt, device = device, results = all, findings = findings,
            summary = handoff(all), confidence = confidence, suggestedIssue = suggestion(all),
        )
    }

    /** How complete the check was: the more guided checks the customer finished, the more the report can say. */
    fun confidence(results: List<DiagnosticResult>): Confidence {
        val done = results.filter { !it.test.info && (it.status == PASS || it.status == ATTENTION) }
        val guided = done.count { it.test.guided }
        val auto = done.count { !it.test.guided }
        return when {
            guided >= 6 && auto >= 4 -> Confidence.HIGH
            guided >= 3 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
    }

    /**
     * A possible repair category from what needed a closer look. Capped at MEDIUM: the shop's inspection decides.
     * Screen and charging come first because the customer confirmed those directly.
     */
    fun suggestion(results: List<DiagnosticResult>): SuggestedIssue? {
        fun attn(t: DiagnosticTest) = results.any { it.test == t && it.status == ATTENTION }
        return when {
            attn(TOUCH) || attn(DISPLAY) -> SuggestedIssue(IssueType.SCREEN, Confidence.MEDIUM, "The touch or display check needed a closer look")
            attn(CHARGING) -> SuggestedIssue(IssueType.CHARGING, Confidence.MEDIUM, "Charging wasn't detected with your charger connected")
            attn(BATTERY) -> SuggestedIssue(IssueType.BATTERY, Confidence.LOW, "Battery readings needed a closer look")
            attn(STORAGE) || attn(MEMORY) -> SuggestedIssue(IssueType.PERFORMANCE, Confidence.LOW,
                if (attn(STORAGE)) "Storage is nearly full" else "Memory was very low during the check")
            listOf(SPEAKER, MICROPHONE, CAMERA, VIBRATION, SENSOR_RESPONSE).any(::attn) ->
                SuggestedIssue(IssueType.OTHER, Confidence.LOW, "A speaker, microphone, camera, vibration or sensor check needed a closer look")
            else -> null
        }
    }

    /**
     * The repair hand-off: what was checked, what needs a closer look and what couldn't be checked, in a few plain
     * sentences, e.g. "Preliminary check completed. Storage is low and the customer reported touch response issues in
     * two areas. Charging was detected. A technician must physically inspect the device before confirming the fault or price."
     */
    fun handoff(results: List<DiagnosticResult>): String {
        val by = results.associateBy { it.test }
        fun attn(t: DiagnosticTest) = by[t]?.status == ATTENTION
        val checks = results.filter { !it.test.info }
        val done = checks.count { it.status == PASS || it.status == ATTENTION }
        val sentences = mutableListOf<String>()

        sentences += when {
            done == 0 -> "Preliminary check started but no checks were completed."
            done == checks.size -> "Preliminary check completed."
            else -> "Preliminary check completed for $done of ${checks.size} checks."
        }

        val concerns = buildList {
            if (attn(STORAGE)) add("storage is low")
            if (attn(MEMORY)) add("memory was very low during the check")
            by[BATTERY]?.takeIf { it.status == ATTENTION }?.let { add(it.note.replaceFirstChar(Char::lowercase)) }
            if (attn(SENSOR_RESPONSE)) add(by.getValue(SENSOR_RESPONSE).note.replaceFirstChar(Char::lowercase))
            if (attn(TOUCH)) {
                val missed = by.getValue(TOUCH).metrics.firstOrNull { it.label == AREAS_MISSED }?.value?.toIntOrNull()
                add("the customer reported touch response issues" + when {
                    missed == null || missed == 0 -> ""
                    missed == 1 -> " in one area"
                    else -> " in ${count(missed)} areas"
                })
            }
            if (attn(DISPLAY)) add("the customer noticed ${by.getValue(DISPLAY).note.lowercase()} on the display")
            if (attn(SPEAKER)) add("the customer reported the test tone was quiet, crackly or silent")
            if (attn(VIBRATION)) add("the customer didn't feel the vibration")
            if (attn(MICROPHONE)) add("the customer reported the recording was muffled, distorted or silent")
            if (attn(CAMERA)) add("the customer reported a camera preview was blurry, dark or not working")
        }
        if (concerns.isNotEmpty()) sentences += listSentence(concerns).replaceFirstChar(Char::uppercase) + "."
        else if (done > 0) sentences += "Nothing checked needed a closer look."

        when (by[CHARGING]?.status) {
            PASS -> sentences += "Charging was detected."
            ATTENTION -> sentences += "Charging wasn't detected with the customer's charger connected."
            UNSUPPORTED -> sentences += "Charging couldn't be verified on this device."
            else -> Unit
        }

        val notChecked = checks.count { it.status == SKIPPED || it.status == UNSUPPORTED }
        if (notChecked > 0) sentences += "$notChecked ${if (notChecked == 1) "check was" else "checks were"} skipped or not available."
        sentences += "A technician must physically inspect the device before confirming the fault or price."
        return sentences.joinToString(" ")
    }

    private fun count(n: Int) = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine").getOrNull(n) ?: n.toString()

    private fun listSentence(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    fun celsius(c: Float) = String.format(Locale.ENGLISH, "%.1f °C", c)
    fun gigabytes(bytes: Long) = String.format(Locale.ENGLISH, "%.1f GB", bytes / 1_000_000_000.0)
    private fun hz(v: Float) = "${v.roundToInt()} Hz"

    private fun patchDate(raw: String): String =
        runCatching { LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) }.getOrDefault(raw)

    private fun buildType(raw: String) = when (raw) {
        "user" -> "Release build (user)"
        "userdebug", "eng" -> "Development build ($raw)"
        else -> raw
    }

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val days = minutes / (60 * 24)
        val hours = (minutes / 60) % 24
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h ${minutes % 60} min"
            else -> "${minutes % 60} min"
        }
    }
}
