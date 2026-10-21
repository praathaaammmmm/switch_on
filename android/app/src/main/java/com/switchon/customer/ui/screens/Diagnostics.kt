package com.switchon.customer.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.DeviceType
import com.switchon.customer.data.diagnostics.*
import com.switchon.customer.data.stamp
import com.switchon.customer.ui.LocalToast
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.leaveFlowTo
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * Where the device check was opened from, which decides where "book" and "back" go. REVIEW shows the report attached
 * to the booking draft and SAVED the one kept with a booked repair, both read-only.
 */
enum class CheckOrigin { HOME, BOOKING, REVIEW, SAVED }

@Composable
fun DiagnosticsScreen(vm: AppViewModel, nav: NavHostController, origin: CheckOrigin, repairId: String? = null) {
    if (origin == CheckOrigin.REVIEW || origin == CheckOrigin.SAVED) {
        val report = (if (origin == CheckOrigin.REVIEW) vm.draft.diagnosticReport else repairId?.let(vm::savedReport)) ?: return MissingReport(nav)
        ScreenScaffold("Device check", { nav.back() }, eyebrow = if (origin == CheckOrigin.REVIEW) "Attached to this booking" else "Saved with $repairId") { ReportDocket(report) }
        return
    }
    val context = LocalContext.current.applicationContext
    val dvm: DiagnosticsViewModel = viewModel { DiagnosticsViewModel(AndroidDiagnosticEngine(context)) }

    BackHandler { if (!dvm.back()) nav.back() }

    when (dvm.phase) {
        DiagnosticPhase.INTRO -> Intro(
            onStart = dvm::start,
            onSkip = { if (origin == CheckOrigin.BOOKING) vm.declineDiagnosticCheck(); nav.back() },
        )
        DiagnosticPhase.RUNNING -> Running(dvm, onExit = { nav.back() })
        DiagnosticPhase.RESULTS -> Results(
            report = dvm.report!!,
            origin = origin,
            onBook = { report ->
                if (origin == CheckOrigin.BOOKING) {
                    vm.attachDiagnosticReport(report, fromBooking = true)
                    nav.back()
                } else {
                    vm.attachDiagnosticReport(report, fromBooking = false)
                    nav.navigate(Routes.BOOK) { popUpTo(Routes.DIAGNOSTICS_PATTERN) { inclusive = true } }
                }
            },
            onAgain = dvm::start,
            onHome = { nav.leaveFlowTo(Routes.HOME) },
        )
    }
}

@Composable
private fun MissingReport(nav: NavHostController) {
    ScreenScaffold("Device check", { nav.back() }) {
        EmptyState("No report", "Nothing attached", "No device check is saved with this booking on this phone.")
    }
}

// ---- Intro ---------------------------------------------------------------------------------------------------

@Composable
private fun Intro(onStart: () -> Unit, onSkip: () -> Unit) {
    ScreenScaffold(
        "Device check", onSkip, eyebrow = "Optional",
        bottom = {
            Btn("Start device check", onStart, Modifier.fillMaxWidth())
            Btn("Skip for now", onSkip, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
        },
    ) {
        Eyebrow("Preliminary device check")
        Display("Check what your phone can tell us.", style = Type.h1)
        Text(
            "A few quick checks on this phone: memory, battery, storage, sensors, touch, display, sound, camera and charging. " +
                "It takes about 3–5 minutes, it's optional, and you can skip any step.",
            style = Type.bodyL, color = SO.paper,
        )
        Panel {
            Text("WHAT IT ISN'T", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.redHot)
            Text(
                "It's a preliminary check of what the phone reports about itself, not a repair diagnosis. It can't see " +
                    "liquid damage, internal cables or the motherboard. The repair shop still inspects the device and confirms the final quote.",
                style = Type.body, color = SO.paper,
            )
        }
        Text("Run it on the phone or tablet you want repaired. Camera and microphone access are asked for only when you reach those checks, and nothing is recorded or uploaded.",
            style = Type.small, color = SO.mute)
    }
}

// ---- Running -------------------------------------------------------------------------------------------------

@Composable
private fun Running(dvm: DiagnosticsViewModel, onExit: () -> Unit) {
    val step = dvm.step
    val done = step.tests.all { it in dvm.results }
    val last = dvm.stepIndex == dvm.stepCount - 1
    ScreenScaffold(
        "Device check", { if (!dvm.back()) onExit() }, eyebrow = "Preliminary device check",
        actions = { SquareIconBtn(Icons.Outlined.Close, "Leave device check", onExit) },
        bottom = {
            Btn(if (last) "See results" else "Continue", { dvm.next() }, Modifier.fillMaxWidth(), enabled = done && dvm.activity == null)
            if (!done) TextAction("Skip this check", { dvm.skipStep() }, SO.mute)
        },
    ) {
        StepBar(dvm.stepIndex + 1, dvm.stepCount, step.label)
        when (step) {
            DiagnosticStep.AUTOMATIC -> AutomaticStep(dvm)
            DiagnosticStep.TOUCH_STEP -> TouchStep(dvm)
            DiagnosticStep.DISPLAY_STEP -> DisplayStep(dvm)
            DiagnosticStep.SPEAKER_STEP -> SpeakerStep(dvm)
            DiagnosticStep.VIBRATION_STEP -> VibrationStep(dvm)
            DiagnosticStep.MICROPHONE_STEP -> MicrophoneStep(dvm)
            DiagnosticStep.CAMERA_STEP -> CameraStep(dvm)
            DiagnosticStep.CHARGING_STEP -> ChargingStep(dvm)
        }
        // Once answered, show the outcome so the customer sees what will go in the report.
        step.tests.mapNotNull { dvm.results[it] }.takeIf { it.isNotEmpty() && step != DiagnosticStep.AUTOMATIC }?.forEach { ResultLine(it) }
    }
}

@Composable
private fun StepIntro(title: String, body: String) {
    Display(title, style = Type.h2)
    Text(body, style = Type.body, color = SO.mute)
}

@Composable
private fun Activity(text: String?) {
    if (text == null) return
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.uppercase(), style = Type.label, color = SO.mute)
        ScanBar()
    }
}

/** A choice that records the result for the current check. */
@Composable
private fun Answer(text: String, onClick: () -> Unit, primary: Boolean = false) =
    Btn(text, onClick, Modifier.fillMaxWidth(), kind = if (primary) BtnKind.PAPER else BtnKind.OUTLINE, icon = null)

@Composable
private fun AutomaticStep(dvm: DiagnosticsViewModel) {
    // Runs in this composition's scope: leaving the screen cancels it and the engine releases the sensors.
    LaunchedEffect(Unit) { dvm.runAutomatic() }
    StepIntro("Automatic checks", "Reading what the phone reports: memory, battery, storage, sensors, connection and hardware details. Pick the phone up and tilt it while the sensors are checked.")
    Activity(dvm.activity)
    val (info, checks) = DiagnosticStep.AUTOMATIC.tests.mapNotNull { dvm.results[it] }.partition { it.test.info }
    checks.forEach { ResultLine(it, showMetrics = true) }
    if (info.isNotEmpty()) {
        Text("DEVICE INFORMATION · NOT REPAIR RESULTS", style = Type.label, color = SO.mute)
        info.forEach { ResultLine(it, showMetrics = true) }
    }
}

// ---- Touch ----

@Composable
private fun TouchStep(dvm: DiagnosticsViewModel) {
    var open by remember { mutableStateOf(false) }
    var coverage by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var untouched by remember { mutableStateOf<Set<Int>>(emptySet()) }
    StepIntro("Touchscreen", "A grid fills the screen. Drag your finger over every square until they all turn red. Tap the ✕ or use Back to stop early.")
    Btn(if (coverage == null) "Start touch test" else "Try the grid again", { open = true }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER, icon = null)
    coverage?.let { (hit, total) ->
        Panel(border = if (hit < total) SO.redHot else SO.line2) {
            Text("$hit of $total areas responded", style = Type.bodyStrong, color = SO.paper)
            if (untouched.isNotEmpty()) {
                Text("Areas that didn't register are outlined in red:", style = Type.small, color = SO.mute)
                CoverageMap(untouched)
            }
        }
        Answer("Everything responded", { dvm.record(DiagnosticRules.touch(true, hit, total)) }, primary = true)
        Answer("Some areas did not respond", { dvm.record(DiagnosticRules.touch(false, hit, total)) })
    }
    if (open) TouchGridDialog(onDone = { hit, total, missed -> coverage = hit to total; untouched = missed; open = false })
}

private const val GRID_COLS = 6
private const val GRID_ROWS = 12

@Composable
private fun CoverageMap(missed: Set<Int>) {
    Canvas(Modifier.width(90.dp).aspectRatio(GRID_COLS / GRID_ROWS.toFloat()).semantics { contentDescription = "${missed.size} areas didn't register" }) {
        val cw = size.width / GRID_COLS; val ch = size.height / GRID_ROWS
        for (i in 0 until GRID_COLS * GRID_ROWS) {
            val o = Offset((i % GRID_COLS) * cw, (i / GRID_COLS) * ch)
            drawRect(if (i in missed) SO.redHot else SO.line, o, Size(cw - 2, ch - 2))
        }
    }
}

/** A test screen that covers the whole display, status and navigation bars included (they return with a swipe). */
@Composable
private fun FullScreen(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                WindowInsetsControllerCompat(w, view).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Full-screen grid. Every cell the finger passes over turns red; the test ends when all are touched or on close. */
@Composable
private fun TouchGridDialog(onDone: (hit: Int, total: Int, missed: Set<Int>) -> Unit) {
    val touched = remember { mutableStateOf(setOf<Int>()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val total = GRID_COLS * GRID_ROWS
    // Cells under the close button can't be touched, so they aren't required. Worked out from where it's drawn.
    var excluded by remember { mutableStateOf(setOf<Int>()) }
    fun finish() {
        val needed = (0 until total).filter { it !in excluded }
        val missed = needed.filter { it !in touched.value }.toSet()
        onDone(needed.size - missed.size, needed.size, missed)
    }
    FullScreen(onClose = ::finish) {
        Box(
            Modifier.fillMaxSize().background(SO.ink)
                // Drags that reach the screen edge shouldn't trigger the system Back gesture mid-test.
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { c ->
                                if (size.width > 0 && size.height > 0) {
                                    val col = (c.position.x / size.width * GRID_COLS).toInt().coerceIn(0, GRID_COLS - 1)
                                    val row = (c.position.y / size.height * GRID_ROWS).toInt().coerceIn(0, GRID_ROWS - 1)
                                    val idx = row * GRID_COLS + col
                                    if (idx !in touched.value) touched.value = touched.value + idx
                                }
                            }
                            if ((0 until total).all { it in touched.value || it in excluded }) { finish(); return@awaitPointerEventScope }
                        }
                    }
                }
                .semantics { contentDescription = "Touch test grid. Drag over every square. ${touched.value.size} of $total touched." },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                size = IntSize(this.size.width.toInt(), this.size.height.toInt())
                val cw = this.size.width / GRID_COLS; val ch = this.size.height / GRID_ROWS
                for (i in 0 until total) {
                    val o = Offset((i % GRID_COLS) * cw, (i / GRID_COLS) * ch)
                    drawRect(if (i in touched.value) SO.red else SO.ink2, o, Size(cw, ch))
                    drawRect(SO.line2, o, Size(cw, ch), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                }
            }
            Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                SquareIconBtn(Icons.Outlined.Close, "Stop touch test", ::finish, tint = SO.paper, border = SO.paper,
                    modifier = Modifier.onGloballyPositioned { c ->
                        if (size.width == 0 || size.height == 0) return@onGloballyPositioned
                        val r = c.boundsInRoot() // the grid fills the dialog, so root coordinates are grid coordinates
                        val cw = size.width / GRID_COLS.toFloat(); val ch = size.height / GRID_ROWS.toFloat()
                        excluded = buildSet {
                            for (row in (r.top / ch).toInt()..((r.bottom - 1) / ch).toInt().coerceAtMost(GRID_ROWS - 1))
                                for (col in (r.left / cw).toInt()..((r.right - 1) / cw).toInt().coerceAtMost(GRID_COLS - 1)) add(row * GRID_COLS + col)
                        }
                    })
            }
        }
    }
}

// ---- Display ----

private val displayColors = listOf("Black" to Color.Black, "White" to Color.White, "Red" to Color(0xFFFF0000), "Green" to Color(0xFF00FF00), "Blue" to Color(0xFF0000FF))
private val displayIssues = listOf("Lines", "Dead pixels", "Tint", "Flicker", "Burn-in")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayStep(dvm: DiagnosticsViewModel) {
    var open by remember { mutableStateOf(false) }
    var seen by rememberSaveable { mutableStateOf(false) }
    var noticed by remember { mutableStateOf(setOf<String>()) }
    StepIntro("Display", "The screen fills with black, white, red, green and blue. Tap to move to the next colour. Look for lines, dead or stuck pixels, a colour tint, flicker or burn-in (faint leftover images).")
    Btn(if (seen) "Show the colours again" else "Start colour test", { open = true }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER, icon = null)
    if (seen) {
        Text("DID YOU NOTICE ANY OF THESE?", style = Type.label, color = SO.mute)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            displayIssues.forEach { i -> Tag(i, i in noticed) { noticed = if (i in noticed) noticed - i else noticed + i } }
        }
        if (noticed.isEmpty()) Answer("None of these. It looked fine", {
            dvm.record(DiagnosticRules.confirmed(DiagnosticTest.DISPLAY, true, "No lines, dead pixels, tint, flicker or burn-in noticed"))
        }, primary = true)
        else Answer("Save what I noticed", {
            dvm.record(DiagnosticRules.confirmed(DiagnosticTest.DISPLAY, false, noticed.joinToString(", "), listOf(DiagnosticMetric("Noticed", noticed.joinToString(", ")))))
        }, primary = true)
    }
    if (open) {
        var i by remember { mutableIntStateOf(0) }
        FullScreen(onClose = { open = false; seen = true }) {
            val (name, color) = displayColors[i]
            Box(
                Modifier.fillMaxSize().background(color)
                    .systemGestureExclusion()
                    .clickable(role = Role.Button, onClickLabel = "Next colour") { if (i < displayColors.lastIndex) i++ else { open = false; seen = true } }
                    .semantics { contentDescription = "$name test screen, ${i + 1} of ${displayColors.size}. Tap for the next colour." },
            ) {
                val ink = if (name == "White" || name == "Green") Color.Black else Color.White
                Row(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}/${displayColors.size} · $name · tap for next".uppercase(), style = Type.label, color = ink.copy(alpha = 0.7f))
                }
                Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                    SquareIconBtn(Icons.Outlined.Close, "Stop colour test", { open = false; seen = true }, tint = ink, border = ink)
                }
            }
        }
    }
}

// ---- Speaker ----

@Composable
private fun SpeakerStep(dvm: DiagnosticsViewModel) {
    val scope = rememberCoroutineScope()
    var played by rememberSaveable { mutableStateOf(false) }
    StepIntro("Speaker", "Turn the media volume up, then play a short tone. Listen for whether it's clear.")
    // Re-read after each play, in case the customer turned the volume up or unplugged headphones.
    val hint = remember(played, dvm.activity) { dvm.speakerHint() }
    if (hint != null) Panel(border = SO.redHot) { Text(hint, style = Type.body, color = SO.paper) }
    Btn(if (played) "Play it again" else "Play tone", { scope.launch { dvm.playTone(); played = true } }, Modifier.fillMaxWidth(),
        kind = BtnKind.PAPER, icon = null, loading = dvm.activity != null)
    if (played) {
        Answer("Heard it clearly", { dvm.record(DiagnosticRules.confirmed(DiagnosticTest.SPEAKER, true, "Tone heard clearly")) }, primary = true)
        Answer("Quiet, crackly or silent", { dvm.record(DiagnosticRules.confirmed(DiagnosticTest.SPEAKER, false, "Tone was quiet, crackly or silent")) })
    }
}

// ---- Vibration ----

@Composable
private fun VibrationStep(dvm: DiagnosticsViewModel) {
    var tried by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { dvm.stopVibration() } }
    LaunchedEffect(Unit) {
        if (!dvm.hasVibrator && DiagnosticTest.VIBRATION !in dvm.results)
            dvm.record(DiagnosticRules.unsupported(DiagnosticTest.VIBRATION, "This device reports no vibration motor"))
    }
    StepIntro("Vibration", "Hold the phone and tap Vibrate. You should feel a short buzz.")
    if (!dvm.hasVibrator) return
    Btn(if (tried) "Vibrate again" else "Vibrate", { dvm.vibrate(); tried = true }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER, icon = null)
    if (tried) {
        Answer("Felt it", { dvm.record(DiagnosticRules.confirmed(DiagnosticTest.VIBRATION, true, "Vibration felt")) }, primary = true)
        Answer("Didn't feel it", { dvm.record(DiagnosticRules.confirmed(DiagnosticTest.VIBRATION, false, "Vibration not felt")) })
    }
}

// ---- Microphone ----

@Composable
private fun MicrophoneStep(dvm: DiagnosticsViewModel) {
    val scope = rememberCoroutineScope()
    var playedBack by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!dvm.hasMicrophone && DiagnosticTest.MICROPHONE !in dvm.results)
            dvm.record(DiagnosticRules.unsupported(DiagnosticTest.MICROPHONE, "No microphone on this device"))
    }
    fun run() = scope.launch { playedBack = dvm.testMicrophone() == MicOutcome.RECORDED_AND_PLAYED }
    // Asked for only now, when the customer starts this check.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        dvm.onPermissionResult(DiagnosticTest.MICROPHONE, granted)
        if (granted) run()
    }
    val context = LocalContext.current
    StepIntro("Microphone", "We'll record about 3 seconds while you speak, then play it straight back. The clip stays on this phone, is never saved or uploaded, and is discarded right after.")
    if (!dvm.hasMicrophone) return
    Btn(if (playedBack) "Record again" else "Start recording", {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) run()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER, icon = null, loading = dvm.activity != null)
    Activity(dvm.activity)
    if (playedBack && dvm.activity == null) {
        Answer("It sounded clear", { dvm.record(DiagnosticRules.confirmed(DiagnosticTest.MICROPHONE, true, "Recording sounded clear")) }, primary = true)
        Answer("Muffled, distorted or silent", { dvm.record(DiagnosticRules.confirmed(DiagnosticTest.MICROPHONE, false, "Recording was muffled, distorted or silent")) })
    }
}

// ---- Camera ----

@Composable
private fun CameraStep(dvm: DiagnosticsViewModel) {
    val context = LocalContext.current
    val cameras = dvm.cameras
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var started by rememberSaveable { mutableStateOf(false) }
    var facing by rememberSaveable { mutableStateOf(if (CameraFacing.BACK in cameras) CameraFacing.BACK else CameraFacing.FRONT) }
    val checked = remember { mutableStateOf(setOf<CameraFacing>()) }
    LaunchedEffect(Unit) {
        if (cameras.isEmpty() && DiagnosticTest.CAMERA !in dvm.results)
            dvm.record(DiagnosticRules.unsupported(DiagnosticTest.CAMERA, "No camera available on this device"))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        dvm.onPermissionResult(DiagnosticTest.CAMERA, ok)
        if (ok) started = true
    }
    StepIntro("Camera", "Point each camera at something with fine detail, like printed text, and check it looks sharp. Nothing is photographed or saved.")
    if (cameras.isEmpty()) return
    if (!started || !granted) {
        Btn("Open the camera", { if (granted) started = true else permission.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER, icon = null)
        return
    }
    if (cameras.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CameraFacing.entries.filter { it in cameras }.forEach { f ->
            Chip("${f.label} camera", facing == f, { facing = f }, Modifier.weight(1f))
        }
    }
    CameraPreview(facing, Modifier.fillMaxWidth().aspectRatio(3f / 4f).border(1.dp, SO.line2), onShown = { checked.value = checked.value + facing })
    val seen = checked.value.joinToString(" and ") { it.label.lowercase() }
    Answer("Sharp and clear", {
        dvm.record(DiagnosticRules.confirmed(DiagnosticTest.CAMERA, true, "Preview sharp ($seen camera)", listOf(DiagnosticMetric("Cameras checked", seen))))
    }, primary = true)
    Answer("Blurry, dark or not working", {
        dvm.record(DiagnosticRules.confirmed(DiagnosticTest.CAMERA, false, "Preview blurry, dark or not working ($seen camera)", listOf(DiagnosticMetric("Cameras checked", seen))))
    })
}

/** Live preview only (no capture). Unbinds the camera when the facing changes or the preview leaves the screen. */
@Composable
private fun CameraPreview(facing: CameraFacing, modifier: Modifier, onShown: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val toast = LocalToast.current
    val view = remember { PreviewView(context) }
    DisposableEffect(facing) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val selector = if (facing == CameraFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider!!.unbindAll()
                provider!!.bindToLifecycle(owner, selector, preview)
                onShown()
            }.onFailure { toast("The ${facing.label.lowercase()} camera couldn't be opened. It may be in use by another app.") }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { provider?.unbindAll() } }
    }
    AndroidView({ view }, modifier.semantics { contentDescription = "${facing.label} camera preview" })
}

// ---- Charging ----

@Composable
private fun ChargingStep(dvm: DiagnosticsViewModel) {
    val scope = rememberCoroutineScope()
    StepIntro("Charging", "Connect your charger and cable to this phone, then tap the button. We'll watch the battery for about 12 seconds. This can show whether charging starts; it can't test the port itself.")
    Btn(if (DiagnosticTest.CHARGING in dvm.results) "Watch again" else "I've connected my charger", { scope.launch { dvm.watchCharging() } },
        Modifier.fillMaxWidth(), kind = BtnKind.PAPER, icon = null, loading = dvm.activity != null)
    Activity(dvm.activity)
    dvm.chargingLive?.takeIf { dvm.activity != null }?.let { b ->
        KeyValue("State", b.state.label)
        KeyValue("Source", b.source.label)
        KeyValue("Current", (b.currentNowMa ?: b.currentAverageMa)?.let { "$it mA" } ?: DiagnosticMetric.NOT_AVAILABLE)
    }
}

// ---- Results ---------------------------------------------------------------------------------------------------

@Composable
private fun Results(report: DiagnosticReport, origin: CheckOrigin, onBook: (DiagnosticReport) -> Unit, onAgain: () -> Unit, onHome: () -> Unit) {
    ScreenScaffold(
        "Device check", onHome, eyebrow = "Your report",
        bottom = {
            Btn(if (origin == CheckOrigin.BOOKING) "Book this repair" else "Book a repair with this report", { onBook(report) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Run checks again", onAgain, Modifier.weight(1f), kind = BtnKind.OUTLINE, icon = null, compact = true)
                Btn("Back to home", onHome, Modifier.weight(1f), kind = BtnKind.OUTLINE, icon = null, compact = true)
            }
        },
    ) {
        ReportDocket(report)
    }
}

/**
 * The report as a paper docket: at a glance, the hand-off summary, grouped results with meaningful readings,
 * device information, notes and the disclaimer.
 */
@Composable
fun ReportDocket(report: DiagnosticReport) {
    val reduced = LocalReducedMotion.current
    Docket(Modifier.fillMaxWidth().scanLine(reduced)) {
        DocketHead("Preliminary device check", stamp(report.checkedAt))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${report.device.manufacturer} ${report.device.model}".uppercase(), style = Type.h3, color = SO.ink)
                Text("${report.device.androidVersion} · ${report.device.deviceType.label}", style = Type.small, color = SO.pencil)
            }
            Stamp("Preliminary", animate = false)
        }
        Text("AT A GLANCE", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.ink)
        Row(Modifier.fillMaxWidth()) {
            DocketField("Checks completed", "${report.completedCount} of ${report.checks.size}", Modifier.weight(1f))
            DocketField("Attention items", "${report.needsAttention.size}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            DocketField("Skipped / not available", "${report.notChecked.size}", Modifier.weight(1f))
            DocketField("Checked", stamp(report.checkedAt), Modifier.weight(1f))
        }
        // Coverage describes how complete the check was, not how sure we are about a fault.
        DocketField("Check coverage", report.confidence.name.lowercase().replaceFirstChar { it.uppercase() })
        Rule(SO.ink, 1.dp)
        Text("REPAIR HAND-OFF SUMMARY", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.ink)
        Text(report.summary, style = Type.bodyStrong, color = SO.ink)
        report.suggestedIssue?.let { s ->
            DocketField("Possible issue", "${s.issue.label} · ${s.confidence.name.lowercase()} confidence\n${s.reason}. Needs physical confirmation.")
        }
        Group("Working normally", report.passed)
        Group("Needs a closer look", report.needsAttention)
        Group("Not checked / not available", report.notChecked)
        Group("Device information", report.deviceInformation, note = "Details the phone reports about itself. Not repair results.")
        if (report.findings.isNotEmpty()) {
            Rule(SO.ink, 1.dp)
            Text("NOTES FOR YOU AND THE SHOP", style = Type.label, color = SO.pencil)
            report.findings.forEach { f ->
                Row(verticalAlignment = Alignment.Top) {
                    Tick(if (f.level == FindingLevel.INFO) SO.pencil else SO.red, 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(f.message, style = Type.small, color = SO.ink, modifier = Modifier.weight(1f))
                }
            }
        }
        DashedRule()
        Text(report.disclaimer, style = Type.small, color = SO.pencil)
    }
}

private val meaningfulMetrics = setOf(
    DiagnosticTest.MEMORY, DiagnosticTest.BATTERY, DiagnosticTest.STORAGE, DiagnosticTest.SENSORS, DiagnosticTest.SENSOR_RESPONSE,
    DiagnosticTest.CHARGING, DiagnosticTest.CONNECTIVITY, DiagnosticTest.TOUCH, DiagnosticTest.CAMERA, DiagnosticTest.DISPLAY,
    DiagnosticTest.DEVICE_INFO, DiagnosticTest.DISPLAY_INFO, DiagnosticTest.CAMERA_INFO, DiagnosticTest.AUDIO_INFO,
)

@Composable
private fun Group(title: String, items: List<DiagnosticResult>, note: String? = null) {
    if (items.isEmpty()) return
    Rule(SO.ink, 1.dp)
    Text("${title.uppercase()} · ${items.size}", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.ink,
        modifier = Modifier.semantics { heading() })
    if (note != null) Text(note, style = Type.small, color = SO.pencil)
    items.forEach { r ->
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.test.label, style = Type.bodyStrong, color = SO.ink)
            Text(r.note, style = Type.small, color = SO.pencil)
            if (r.test in meaningfulMetrics) r.metrics.forEach { m ->
                Row {
                    Text(m.label.uppercase() + "  ", style = Type.label.copy(fontSize = 10.sp), color = SO.pencil)
                    Text(m.value, style = Type.label.copy(fontSize = 10.sp), color = if (m.value == DiagnosticMetric.NOT_AVAILABLE) SO.pencil else SO.ink, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
private fun ResultLine(r: DiagnosticResult, showMetrics: Boolean = false) {
    val color = when (r.status) { DiagnosticStatus.PASS -> SO.paper; DiagnosticStatus.ATTENTION -> SO.redHot; else -> SO.mute }
    val tag = if (r.status == DiagnosticStatus.INFO) "Info" else r.status.label
    Column(Modifier.fillMaxWidth().border(1.dp, if (r.status == DiagnosticStatus.ATTENTION) SO.redHot else SO.line2).padding(12.dp)
        .semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.test.label, style = Type.bodyStrong, color = SO.paper, modifier = Modifier.weight(1f))
            StatusTag(tag, color)
        }
        Text(r.note, style = Type.small, color = SO.mute)
        if (showMetrics && r.test in meaningfulMetrics) r.metrics.forEach { KeyValue(it.label, it.value, valueColor = if (it.value == DiagnosticMetric.NOT_AVAILABLE) SO.mute else SO.paper) }
    }
}

// ---- Booking hand-off ------------------------------------------------------------------------------------------

/** Shown in booking right after the device is chosen. Never blocks: the customer can always continue without it. */
@Composable
fun DeviceCheckOffer(vm: AppViewModel, nav: NavHostController) {
    val device = vm.draft.device ?: return
    val supported = device == DeviceType.PHONE || device == DeviceType.TABLET
    Display(if (supported) "Run a quick check first?" else "We'll diagnose it at the shop", style = Type.h1)
    if (supported) {
        Text("A free, optional device check takes 3–5 minutes on this ${device.label.lowercase()}. It can help the shop, but it's preliminary: the final diagnosis and quote come after a physical inspection.",
            style = Type.body, color = SO.mute)
        Btn("Run a free device check first", { nav.go(Routes.diagnostics(CheckOrigin.BOOKING)) }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER)
        Btn("Continue without a check", { vm.declineDiagnosticCheck() }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
        Text("Run it on the device you want repaired. If it isn't working well enough, just continue.", style = Type.small, color = SO.mute)
    } else {
        Text("Guided checks for ${device.label.lowercase()}s are coming in a later update. For now, tell us what's wrong in the next steps and the repair shop will diagnose it after pickup.",
            style = Type.body, color = SO.mute)
        Btn("Continue", { vm.declineDiagnosticCheck() }, Modifier.fillMaxWidth())
    }
}

/**
 * Concise summary of a device check for the booking review and the tracker: counts, the hand-off summary and a link
 * to the full report. [saved] is the line about where the report lives.
 */
@Composable
fun AttachedCheckSummary(
    report: DiagnosticReport,
    onView: () -> Unit,
    saved: String = "Kept with this booking on your phone only; it isn't sent to the shop. It doesn't change your estimate.",
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("PRELIMINARY DEVICE CHECK", style = Type.label, color = SO.pencil)
        val attention = report.needsAttention.size
        Text("${report.completedCount} of ${report.checks.size} checks done · " + when (attention) {
            0 -> "none need a closer look"; 1 -> "1 needs a closer look"; else -> "$attention need a closer look"
        }, style = Type.bodyStrong, color = SO.ink)
        Text(report.summary, style = Type.small, color = SO.ink)
        Text(saved, style = Type.small, color = SO.pencil)
        Box(Modifier.height(48.dp).clickable(role = Role.Button, onClickLabel = "View device check report", onClick = onView), contentAlignment = Alignment.CenterStart) {
            Text("VIEW REPORT", style = Type.label.copy(fontWeight = FontWeight.Bold, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = SO.red)
        }
    }
}
