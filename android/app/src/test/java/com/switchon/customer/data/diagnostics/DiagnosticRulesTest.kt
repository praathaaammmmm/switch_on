package com.switchon.customer.data.diagnostics

import com.switchon.customer.data.DeviceType
import com.switchon.customer.data.IssueType
import com.switchon.customer.data.diagnostics.DiagnosticStatus.*
import com.switchon.customer.data.diagnostics.DiagnosticTest.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DiagnosticRulesTest {

    private val device = DeviceInfo("Google", "Pixel 7", "Android 15", 35, DeviceType.PHONE)
    private val at = LocalDateTime.of(2026, 10, 22, 10, 30)

    private fun battery(temp: Float? = 31f, health: BatteryHealth = BatteryHealth.GOOD, now: Int? = null, avg: Int? = null,
                        state: ChargeState = ChargeState.DISCHARGING, source: PowerSource = PowerSource.NONE) =
        BatterySnapshot(76, state, source, temp, health, now, avg)

    // ---- Unsupported properties ----

    @Test fun unavailableBatteryCurrentIsNotAvailableNotAFailure() {
        val r = DiagnosticRules.battery(battery(now = null, avg = null))
        assertEquals(PASS, r.status)
        assertEquals(DiagnosticMetric.NOT_AVAILABLE, r.metrics.first { it.label == "Current now" }.value)
        assertEquals("Not available on this device", r.metrics.first { it.label == "Average current" }.value)
    }

    @Test fun missingSystemDataIsUnsupported() {
        assertEquals(UNSUPPORTED, DiagnosticRules.battery(null).status)
        assertEquals(UNSUPPORTED, DiagnosticRules.storage(null).status)
        assertEquals(UNSUPPORTED, DiagnosticRules.sensors(SensorKind.entries.associateWith { false }).status)
        assertEquals(UNSUPPORTED, DiagnosticRules.sensorResponse(emptyMap(), emptyMap()).status)
        assertEquals(UNSUPPORTED, DiagnosticRules.charging(emptyList()).status)
        assertEquals("Unable to verify charging on this device", DiagnosticRules.charging(emptyList()).note)
    }

    @Test fun missingSensorsAreNotFittedNotFaulty() {
        val r = DiagnosticRules.sensors(mapOf(SensorKind.ACCELEROMETER to true, SensorKind.GYROSCOPE to false))
        assertEquals(PASS, r.status)
        assertEquals("Not available on this model", r.metrics.first { it.label == "Gyroscope" }.value)
    }

    // ---- Warnings ----

    @Test fun batteryTemperatureWarningsScaleWithHeat() {
        assertTrue(DiagnosticRules.batteryFindings(battery(temp = 30f)).isEmpty())
        val warm = DiagnosticRules.batteryFindings(battery(temp = 41f))
        assertEquals(listOf(FindingLevel.WARNING), warm.map { it.level })
        assertEquals(PASS, DiagnosticRules.battery(battery(temp = 41f)).status)
        val hot = DiagnosticRules.batteryFindings(battery(temp = 47.5f))
        assertEquals(listOf(FindingLevel.RED_FLAG), hot.map { it.level })
        assertEquals(ATTENTION, DiagnosticRules.battery(battery(temp = 47.5f)).status)
        assertTrue(hot.single().message.contains("may indicate"))
    }

    @Test fun reportedBatteryHealthProblemIsARedFlag() {
        val f = DiagnosticRules.batteryFindings(battery(health = BatteryHealth.OVERHEAT))
        assertEquals(FindingLevel.RED_FLAG, f.single().level)
        assertEquals(ATTENTION, DiagnosticRules.battery(battery(health = BatteryHealth.OVERHEAT)).status)
        assertTrue(DiagnosticRules.batteryFindings(battery(health = BatteryHealth.UNKNOWN)).isEmpty())
    }

    @Test fun lowStorageIsAPerformanceWarning() {
        val gb = 1_000_000_000L
        val low = StorageSnapshot(freeBytes = 3 * gb, totalBytes = 128 * gb) // under 10%
        assertEquals(ATTENTION, DiagnosticRules.storage(low).status)
        assertTrue(DiagnosticRules.storageFindings(low).single().message.contains("slow"))
        val tight = StorageSnapshot(freeBytes = gb, totalBytes = 8 * gb) // under 2 GB
        assertEquals(ATTENTION, DiagnosticRules.storage(tight).status)
        val fine = StorageSnapshot(freeBytes = 40 * gb, totalBytes = 128 * gb)
        assertEquals(PASS, DiagnosticRules.storage(fine).status)
        assertTrue(DiagnosticRules.storageFindings(fine).isEmpty())
    }

    @Test fun silentSensorNeedsACloserLook() {
        val present = mapOf(SensorKind.ACCELEROMETER to true, SensorKind.PROXIMITY to true, SensorKind.GYROSCOPE to false)
        val r = DiagnosticRules.sensorResponse(present, mapOf(SensorKind.ACCELEROMETER to true, SensorKind.PROXIMITY to false))
        assertEquals(ATTENTION, r.status)
        assertTrue(r.note.startsWith("Proximity"))
        assertEquals(2, r.metrics.size) // only sensors that exist are tested
        assertEquals(PASS, DiagnosticRules.sensorResponse(present, mapOf(SensorKind.ACCELEROMETER to true, SensorKind.PROXIMITY to true)).status)
    }

    @Test fun offlineIsNotAHardwareFault() {
        assertEquals(SKIPPED, DiagnosticRules.connectivity(ConnectivitySnapshot(false, null)).status)
        val online = DiagnosticRules.connectivity(ConnectivitySnapshot(true, Transport.WIFI))
        assertEquals(PASS, online.status)
        assertEquals("Online via Wi-Fi", online.note)
        assertTrue(DiagnosticRules.connectivityFinding.message.contains("doesn't test"))
    }

    @Test fun chargingIsDetectedOrNotNeverDiagnosed() {
        val charging = DiagnosticRules.charging(listOf(battery(), battery(state = ChargeState.CHARGING, source = PowerSource.AC, now = 1450)))
        assertEquals(PASS, charging.status)
        assertEquals("about 1450 mA", charging.metrics.first { it.label == "Charging current" }.value)
        val notCharging = DiagnosticRules.charging(listOf(battery(), battery()))
        assertEquals(ATTENTION, notCharging.status)
        assertEquals("Charging not detected", notCharging.note)
    }

    // ---- Report aggregation ----

    private fun guided(test: DiagnosticTest, ok: Boolean) = DiagnosticRules.confirmed(test, ok, if (ok) "OK" else "Problem")

    private fun automatic() = listOf(
        DiagnosticRules.deviceInfo(device), DiagnosticRules.battery(battery()), DiagnosticRules.storage(StorageSnapshot(40_000_000_000, 128_000_000_000)),
        DiagnosticRules.sensors(mapOf(SensorKind.ACCELEROMETER to true)),
        DiagnosticRules.sensorResponse(mapOf(SensorKind.ACCELEROMETER to true), mapOf(SensorKind.ACCELEROMETER to true)),
        DiagnosticRules.connectivity(ConnectivitySnapshot(true, Transport.WIFI)),
    )

    private fun report(vararg rs: DiagnosticResult) = DiagnosticRules.buildReport(device, rs.associateBy { it.test }, emptyList(), at)

    @Test fun reportGroupsEveryTestAndFillsGaps() {
        val r = report(*automatic().toTypedArray(), guided(TOUCH, true), guided(DISPLAY, false), DiagnosticRules.permissionDenied(CAMERA))
        assertEquals(DiagnosticTest.entries.size, r.results.size)
        assertEquals(6, r.passed.size)          // 5 automatic checks + touch; device software is information
        assertEquals(listOf(DISPLAY), r.needsAttention.map { it.test })
        assertEquals("Permission not granted", r.results.first { it.test == CAMERA }.note)
        assertEquals("Not run", r.results.first { it.test == SPEAKER }.note)
        assertEquals(r.checks.size - 7, r.notChecked.size)
        assertEquals(2, r.userConfirmed.size)
        assertEquals(7, r.completedCount)
        assertEquals(DiagnosticTest.entries.count { it.info }, r.deviceInformation.size)
        assertTrue(r.deviceInformation.all { it.status == INFO })
        assertEquals(at, r.checkedAt)
        assertTrue(r.disclaimer.contains("not a physical repair diagnosis"))
    }

    private val gb = 1_000_000_000L
    private val lowStorage = StorageSnapshot(3 * gb, 128 * gb)
    private val plentyOfMemory = MemorySnapshot(3 * gb, 8 * gb, systemLowMemory = false)

    /** Every check run, with storage low, two touch areas missed and charging detected: the agreed example. */
    @Test fun handoffSummaryMatchesTheAgreedExample() {
        val all = listOf(
            DiagnosticRules.memory(plentyOfMemory), DiagnosticRules.battery(battery()), DiagnosticRules.storage(lowStorage),
            DiagnosticRules.sensors(mapOf(SensorKind.ACCELEROMETER to true)),
            DiagnosticRules.sensorResponse(mapOf(SensorKind.ACCELEROMETER to true), mapOf(SensorKind.ACCELEROMETER to true)),
            DiagnosticRules.connectivity(ConnectivitySnapshot(true, Transport.WIFI)),
            DiagnosticRules.touch(ok = false, hit = 68, total = 70),
            DiagnosticRules.charging(listOf(battery(state = ChargeState.CHARGING, source = PowerSource.AC, now = 1200))),
        ) + listOf(DISPLAY, SPEAKER, VIBRATION, MICROPHONE, CAMERA).map { guided(it, true) }
        assertEquals(
            "Preliminary check completed. Storage is low and the customer reported touch response issues in two areas. " +
                "Charging was detected. A technician must physically inspect the device before confirming the fault or price.",
            report(*all.toTypedArray()).summary,
        )
    }

    @Test fun handoffSummaryCountsWhatWasntChecked() {
        val r = report(
            DiagnosticRules.charging(listOf(battery(state = ChargeState.CHARGING, source = PowerSource.USB))),
            guided(TOUCH, true), guided(SPEAKER, true), guided(VIBRATION, true),
        )
        val checks = DiagnosticTest.entries.count { !it.info }
        assertEquals(
            "Preliminary check completed for 4 of $checks checks. Nothing checked needed a closer look. Charging was detected. " +
                "${checks - 4} checks were skipped or not available. A technician must physically inspect the device before confirming the fault or price.",
            r.summary,
        )
        assertTrue(report().summary.startsWith("Preliminary check started but no checks were completed."))
    }

    @Test fun deviceInformationNeverCountsAsACheck() {
        val r = report(
            DiagnosticRules.deviceInfo(device, SoftwareSnapshot("2025-09-05", "user", 90_000_000)),
            DiagnosticRules.display(DisplaySnapshot(1080, 2400, 420, 60f, 120f, 40, true)),
            DiagnosticRules.cameraInfo(listOf(CameraDetail(CameraFacing.BACK, true, "Full", 1920, 1080))),
            DiagnosticRules.audioInfo(AudioSnapshot(AudioRoute.SPEAKER, true, true, 60)),
        )
        assertEquals(0, r.completedCount)
        assertEquals(4, r.deviceInformation.size)
        assertTrue(r.notChecked.none { it.test.info })
        assertEquals(Confidence.LOW, r.confidence)
        val software = r.deviceInformation.first { it.test == DEVICE_INFO }
        assertTrue(software.note.contains("not a repair result"))
        assertEquals("5 Sep 2025", software.metrics.first { it.label == "Security patch" }.value)
        assertEquals("Release build (user)", software.metrics.first { it.label == "Build type" }.value)
        assertEquals("1 d 1 h", software.metrics.first { it.label == "Uptime" }.value)
        val display = r.deviceInformation.first { it.test == DISPLAY_INFO }
        assertEquals("1080 × 2400 px", display.metrics.first { it.label == "Resolution" }.value)
        assertEquals("60 Hz (supports up to 120 Hz)", display.metrics.first { it.label == "Refresh rate" }.value)
        assertEquals("40% · adaptive on", display.metrics.first { it.label == "Brightness" }.value)
        assertEquals("Flash · Full camera support · Preview up to 1920 × 1080",
            r.deviceInformation.first { it.test == CAMERA_INFO }.metrics.single().value)
    }

    // ---- Unsupported properties (new readings) ----

    @Test fun unexposedBatteryDetailsAreNotAvailableAndNeverInferred() {
        val r = DiagnosticRules.battery(battery())
        listOf("Voltage", "Charge cycles", "Charge remaining").forEach {
            assertEquals(it, DiagnosticMetric.NOT_AVAILABLE, r.metrics.first { m -> m.label == it }.value)
        }
        assertEquals(PASS, r.status)
        val full = DiagnosticRules.battery(battery().copy(voltageMv = 4123, cycleCount = 312, chargeCounterMah = 3010))
        assertEquals("4.12 V", full.metrics.first { it.label == "Voltage" }.value)
        assertEquals("312", full.metrics.first { it.label == "Charge cycles" }.value)
        assertEquals("3010 mAh (as reported)", full.metrics.first { it.label == "Charge remaining" }.value)
        assertTrue("no health percentage is ever derived", full.metrics.none { it.value.contains("health", ignoreCase = true) })
    }

    @Test fun unexposedDeviceDetailsAreNotAvailable() {
        val display = DiagnosticRules.display(DisplaySnapshot(null, null, null, null, null, null, null))
        assertEquals(INFO, display.status)
        assertTrue(display.metrics.all { it.value == DiagnosticMetric.NOT_AVAILABLE })
        assertEquals("Display details aren't available on this device", DiagnosticRules.display(null).note)
        val software = DiagnosticRules.deviceInfo(device, SoftwareSnapshot(null, null, null))
        assertEquals(DiagnosticMetric.NOT_AVAILABLE, software.metrics.first { it.label == "Security patch" }.value)
        assertEquals(DiagnosticMetric.NOT_AVAILABLE, DiagnosticRules.cameraInfo(listOf(CameraDetail(CameraFacing.FRONT, null, null, null, null))).metrics.single().value)
        assertEquals(INFO, DiagnosticRules.cameraInfo(emptyList()).status)
        val audio = DiagnosticRules.audioInfo(AudioSnapshot(null, null, null, null))
        assertTrue(audio.metrics.all { it.value == DiagnosticMetric.NOT_AVAILABLE })
        assertEquals(UNSUPPORTED, DiagnosticRules.memory(null).status)
        assertEquals(UNSUPPORTED, DiagnosticRules.memory(MemorySnapshot(0, 0, false)).status)
    }

    @Test fun wifiSignalIsShownOnlyWhenExposed() {
        val wifi = DiagnosticRules.connectivity(ConnectivitySnapshot(true, Transport.WIFI, validated = true, wifiRssiDbm = -61))
        assertEquals("Good · -61 dBm", wifi.metrics.first { it.label == "Wi-Fi signal" }.value)
        assertEquals("Reachable", wifi.metrics.first { it.label == "Internet" }.value)
        val hidden = DiagnosticRules.connectivity(ConnectivitySnapshot(true, Transport.WIFI, validated = null, wifiRssiDbm = null))
        assertEquals(DiagnosticMetric.NOT_AVAILABLE, hidden.metrics.first { it.label == "Wi-Fi signal" }.value)
        assertTrue(hidden.metrics.none { it.label == "Internet" })
        val mobile = DiagnosticRules.connectivity(ConnectivitySnapshot(true, Transport.CELLULAR, wifiRssiDbm = -40))
        assertTrue(mobile.metrics.none { it.label == "Wi-Fi signal" })
        assertEquals("Weak", DiagnosticRules.wifiBand(-82))
        assertTrue("a weak signal is never a fault", DiagnosticRules.connectivity(ConnectivitySnapshot(true, Transport.WIFI, wifiRssiDbm = -88)).status == PASS)
    }

    // ---- Memory ----

    @Test fun memoryWarnsOnlyWhenGenuinelyLow() {
        assertEquals(PASS, DiagnosticRules.memory(plentyOfMemory).status)
        assertTrue(DiagnosticRules.memoryFindings(plentyOfMemory).isEmpty())
        val flagged = MemorySnapshot(2 * gb, 8 * gb, systemLowMemory = true) // Android itself says memory is low
        assertEquals(ATTENTION, DiagnosticRules.memory(flagged).status)
        val tight = MemorySnapshot(gb / 2, 8 * gb, systemLowMemory = false) // about 6% available
        assertEquals(ATTENTION, DiagnosticRules.memory(tight).status)
        val f = DiagnosticRules.memoryFindings(tight).single()
        assertEquals(FindingLevel.WARNING, f.level)
        assertTrue(f.message.contains("may"))
        assertTrue(f.message.contains("doesn't point to a hardware problem"))
        assertEquals(PASS, DiagnosticRules.memory(MemorySnapshot((0.11 * 8 * gb).toLong(), 8 * gb, false)).status) // just above the line
        assertEquals(IssueType.PERFORMANCE, report(DiagnosticRules.memory(tight)).suggestedIssue!!.issue)
    }

    // ---- Audio ----

    @Test fun audioRoutingAndMutedVolumeAreFlaggedForTheSpeakerCheck() {
        assertNull(DiagnosticRules.speakerHint(AudioSnapshot(AudioRoute.SPEAKER, true, true, 50)))
        assertTrue(DiagnosticRules.speakerHint(AudioSnapshot(AudioRoute.SPEAKER, true, true, 0))!!.contains("muted"))
        assertTrue(DiagnosticRules.speakerHint(AudioSnapshot(AudioRoute.BLUETOOTH, true, true, 50))!!.contains("bluetooth audio"))
        val findings = DiagnosticRules.audioFindings(AudioSnapshot(AudioRoute.WIRED, true, true, 0))
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.level == FindingLevel.INFO })
        assertEquals("Muted", DiagnosticRules.audioInfo(AudioSnapshot(AudioRoute.SPEAKER, true, true, 0)).metrics.first { it.label == "Media volume" }.value)
    }

    @Test fun reportsSurviveSavingOnThePhone() {
        val r = report(*automatic().toTypedArray(), DiagnosticRules.touch(false, 60, 70), DiagnosticRules.permissionDenied(CAMERA))
        val json = kotlinx.serialization.json.Json.encodeToString(DiagnosticReport.serializer(), r)
        assertEquals(r, kotlinx.serialization.json.Json.decodeFromString(DiagnosticReport.serializer(), json))
    }

    @Test fun wordingNeverClaimsAPartIsBroken() {
        val everythingWrong = DiagnosticRules.buildReport(device,
            (DiagnosticTest.entries.filter { it.guided && it != CHARGING && it != TOUCH }.map { guided(it, false) } + listOf(
                DiagnosticRules.touch(false, 50, 70),
                DiagnosticRules.charging(listOf(battery())), DiagnosticRules.battery(battery(temp = 50f, health = BatteryHealth.DEAD)),
                DiagnosticRules.memory(MemorySnapshot(100_000_000, 8 * gb, true)), DiagnosticRules.storage(lowStorage),
                DiagnosticRules.sensorResponse(mapOf(SensorKind.ROTATION to true), emptyMap()),
                DiagnosticRules.audioInfo(AudioSnapshot(AudioRoute.BLUETOOTH, false, false, 0)),
            )).associateBy { it.test },
            DiagnosticRules.memoryFindings(MemorySnapshot(100_000_000, 8 * gb, true)) + DiagnosticRules.storageFindings(lowStorage) +
                DiagnosticRules.audioFindings(AudioSnapshot(AudioRoute.BLUETOOTH, false, false, 0)) + DiagnosticRules.connectivityFinding, at)
        val text = (listOf(everythingWrong.summary) + everythingWrong.findings.map { it.message } + everythingWrong.results.map { it.note }).joinToString(" ").lowercase()
        assertTrue(everythingWrong.summary.contains("must physically inspect"))
        listOf("broken", "failed", "faulty", "defective", "damaged").forEach { assertFalse("contains \"$it\"", text.contains(it)) }
        assertTrue(everythingWrong.findings.filter { it.level != FindingLevel.INFO }.all { it.message.contains("may") || it.message.contains("closer look") })
    }

    @Test fun confidenceReflectsHowMuchWasChecked() {
        assertEquals(Confidence.LOW, report(*automatic().toTypedArray()).confidence)
        assertEquals(Confidence.MEDIUM, report(*automatic().toTypedArray(), guided(TOUCH, true), guided(DISPLAY, true), guided(SPEAKER, true)).confidence)
        val all = automatic() + DiagnosticTest.entries.filter { it.guided }.map { guided(it, true) }
        assertEquals(Confidence.HIGH, report(*all.toTypedArray()).confidence)
    }

    @Test fun suggestionsAreCappedAndPrioritised() {
        assertNull(report(*automatic().toTypedArray(), guided(TOUCH, true)).suggestedIssue)
        val screen = report(guided(TOUCH, false), DiagnosticRules.charging(listOf(battery()))).suggestedIssue!!
        assertEquals(IssueType.SCREEN, screen.issue) // screen outranks charging
        assertEquals(IssueType.CHARGING, report(DiagnosticRules.charging(listOf(battery()))).suggestedIssue!!.issue)
        assertEquals(IssueType.PERFORMANCE, report(DiagnosticRules.storage(StorageSnapshot(500_000_000, 64_000_000_000))).suggestedIssue!!.issue)
        // Even with everything checked, a suggestion never claims high confidence.
        val all = automatic() + DiagnosticTest.entries.filter { it.guided }.map { guided(it, it != DISPLAY) }
        val r = report(*all.toTypedArray())
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.suggestedIssue!!.confidence != Confidence.HIGH)
    }

    @Test fun findingsPutRedFlagsFirst() {
        val r = DiagnosticRules.buildReport(device, mapOf(TOUCH to guided(TOUCH, false)),
            DiagnosticRules.batteryFindings(battery(temp = 48f)) + DiagnosticRules.connectivityFinding, at)
        assertEquals(FindingLevel.RED_FLAG, r.findings.first().level)
        assertEquals(FindingLevel.INFO, r.findings.last().level)
        assertEquals(2, r.warnings.size)
        assertEquals(1, r.redFlags.size)
    }
}
