package com.switchon.customer.data.diagnostics

import com.switchon.customer.data.DeviceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** A phone with no gyroscope, no exposed charging current, and a scriptable microphone. */
class FakeEngine : DiagnosticEngine {
    var mic = MicOutcome.RECORDED_AND_PLAYED
    var hangSensors = false
    var sensorsReleased = false
    val sensorsStarted = CompletableDeferred<Unit>()
    var vibrations = 0
    var vibrationCancelled = false
    var chargingSamples = listOf(BatterySnapshot(60, ChargeState.CHARGING, PowerSource.USB, 30f, BatteryHealth.GOOD, null, null))

    var audio: AudioSnapshot? = AudioSnapshot(AudioRoute.SPEAKER, true, true, 50)

    override fun deviceInfo() = DeviceInfo("Google", "Pixel 7", "Android 15", 35, DeviceType.PHONE)
    override fun software() = SoftwareSnapshot("2025-09-05", "user", 3_600_000)
    // 6% available: low enough to warn.
    override fun memory() = MemorySnapshot(500_000_000, 8_000_000_000, systemLowMemory = false)
    // A phone that hides its display settings.
    override fun display(): DisplaySnapshot? = null
    override fun cameraDetails() = listOf(CameraDetail(CameraFacing.BACK, true, "Full", 1920, 1080), CameraDetail(CameraFacing.FRONT, false, "Limited", 1280, 720))
    override fun audio() = audio
    override fun battery() = BatterySnapshot(76, ChargeState.DISCHARGING, PowerSource.NONE, 31f, BatteryHealth.GOOD, null, null)
    override fun storage() = StorageSnapshot(40_000_000_000, 128_000_000_000)
    override fun sensorsPresent() = SensorKind.entries.associateWith { it != SensorKind.GYROSCOPE }
    override suspend fun sensorResponse(kinds: Set<SensorKind>, windowMs: Long): Map<SensorKind, Boolean> {
        try {
            sensorsStarted.complete(Unit)
            if (hangSensors) awaitCancellation()
            return kinds.associateWith { true }
        } finally {
            sensorsReleased = true
        }
    }
    override fun connectivity() = ConnectivitySnapshot(true, Transport.WIFI)
    override suspend fun observeCharging(seconds: Int, onSample: (BatterySnapshot?) -> Unit) = chargingSamples.onEach(onSample)
    override val hasVibrator = true
    override fun vibrate() { vibrations++ }
    override fun cancelVibration() { vibrationCancelled = true }
    override suspend fun playTone(durationMs: Int) = Unit
    override val hasMicrophone = true
    override suspend fun recordAndPlayBack(recordMs: Int, onPhase: (String) -> Unit) = mic
}

class DiagnosticsViewModelTest {
    private val engine = FakeEngine()
    private val vm = DiagnosticsViewModel(engine, clock = { LocalDateTime.of(2026, 10, 22, 10, 0) })

    @Test fun automaticChecksCollectEveryAutomaticResult() = runTest {
        vm.start()
        vm.runAutomatic()
        assertTrue(vm.automaticDone)
        assertEquals(DiagnosticStep.AUTOMATIC.tests.toSet(), vm.results.keys)
        assertEquals("Not available on this model", vm.results.getValue(DiagnosticTest.SENSORS).metrics.first { it.label == "Gyroscope" }.value)
        assertEquals(DiagnosticStatus.PASS, vm.results.getValue(DiagnosticTest.SENSOR_RESPONSE).status)
        assertTrue(engine.sensorsReleased)
        assertEquals(DiagnosticStatus.ATTENTION, vm.results.getValue(DiagnosticTest.MEMORY).status)
        assertEquals("Display details aren't available on this device", vm.results.getValue(DiagnosticTest.DISPLAY_INFO).note)
        assertEquals(setOf(CameraFacing.BACK, CameraFacing.FRONT), vm.cameras)
        listOf(DiagnosticTest.DEVICE_INFO, DiagnosticTest.DISPLAY_INFO, DiagnosticTest.CAMERA_INFO, DiagnosticTest.AUDIO_INFO).forEach {
            assertEquals(DiagnosticStatus.INFO, vm.results.getValue(it).status)
        }
    }

    @Test fun leavingDuringTheSensorCheckReleasesTheSensors() = runTest {
        engine.hangSensors = true
        vm.start()
        val job = launch { vm.runAutomatic() }
        engine.sensorsStarted.await()
        job.cancel()
        job.join()
        assertTrue(engine.sensorsReleased)
        assertFalse(vm.automaticDone)
        assertEquals(null, vm.activity)
    }

    @Test fun deniedPermissionSkipsTheCheckAndTheFlowContinues() {
        vm.start()
        vm.onPermissionResult(DiagnosticTest.CAMERA, granted = false)
        val r = vm.results.getValue(DiagnosticTest.CAMERA)
        assertEquals(DiagnosticStatus.SKIPPED, r.status)
        assertEquals("Permission not granted", r.note)
        vm.onPermissionResult(DiagnosticTest.MICROPHONE, granted = true)
        assertFalse(DiagnosticTest.MICROPHONE in vm.results) // granted: the check itself decides
    }

    @Test fun microphoneOutcomesAreRecordedHonestly() = runTest {
        vm.start()
        engine.mic = MicOutcome.NO_MICROPHONE
        vm.testMicrophone()
        assertEquals(DiagnosticStatus.UNSUPPORTED, vm.results.getValue(DiagnosticTest.MICROPHONE).status)
        engine.mic = MicOutcome.PERMISSION_DENIED
        vm.testMicrophone()
        assertEquals("Permission not granted", vm.results.getValue(DiagnosticTest.MICROPHONE).note)
        vm.results.remove(DiagnosticTest.MICROPHONE)
        engine.mic = MicOutcome.RECORDED_AND_PLAYED
        assertEquals(MicOutcome.RECORDED_AND_PLAYED, vm.testMicrophone())
        assertFalse("the customer's answer is the result", DiagnosticTest.MICROPHONE in vm.results)
    }

    @Test fun chargingWithoutCurrentIsDetectedButUnmeasured() = runTest {
        vm.start()
        vm.watchCharging()
        val r = vm.results.getValue(DiagnosticTest.CHARGING)
        assertEquals(DiagnosticStatus.PASS, r.status)
        assertEquals(DiagnosticMetric.NOT_AVAILABLE, r.metrics.first { it.label == "Charging current" }.value)
        assertNotNull(vm.chargingLive)
    }

    @Test fun skippingAndFinishingProducesACompleteReport() = runTest {
        vm.start()
        vm.runAutomatic()
        vm.next()
        vm.record(DiagnosticRules.confirmed(DiagnosticTest.TOUCH, true, "Every area responded"))
        vm.next()
        repeat(vm.stepCount - 2) { vm.skipStep() }
        assertEquals(DiagnosticPhase.RESULTS, vm.phase)
        val report = vm.report!!
        assertEquals(DiagnosticTest.entries.size, report.results.size)
        assertEquals(DiagnosticStatus.SKIPPED, report.results.first { it.test == DiagnosticTest.SPEAKER }.status)
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 0), report.checkedAt)
        assertTrue(report.findings.any { it.test == DiagnosticTest.CONNECTIVITY && it.level == FindingLevel.INFO })
    }

    @Test fun backStepsThroughTheFlowThenOut() {
        vm.start()
        vm.next(); vm.next()
        assertEquals(2, vm.stepIndex)
        assertTrue(vm.back()); assertTrue(vm.back())
        assertEquals(0, vm.stepIndex)
        assertTrue(vm.back())
        assertEquals(DiagnosticPhase.INTRO, vm.phase)
        assertFalse(vm.back())
    }

    @Test fun lowMemoryReachesTheReportAsAWarning() = runTest {
        vm.start()
        vm.runAutomatic()
        repeat(vm.stepCount) { if (vm.phase == DiagnosticPhase.RUNNING) vm.skipStep() }
        val report = vm.report!!
        assertTrue(report.findings.any { it.test == DiagnosticTest.MEMORY && it.level == FindingLevel.WARNING })
        assertTrue(report.summary.contains("Memory was very low during the check"))
    }

    @Test fun speakerHintFollowsTheCurrentAudioState() {
        assertEquals(null, vm.speakerHint())
        engine.audio = AudioSnapshot(AudioRoute.WIRED, true, true, 50)
        assertTrue(vm.speakerHint()!!.contains("wired headphones"))
        engine.audio = null
        assertEquals(null, vm.speakerHint())
    }

    @Test fun vibrationIsStoppable() {
        vm.vibrate(); vm.stopVibration()
        assertEquals(1, engine.vibrations)
        assertTrue(engine.vibrationCancelled)
    }
}
