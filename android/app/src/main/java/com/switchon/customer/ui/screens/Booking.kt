package com.switchon.customer.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.switchon.customer.data.BusyException
import com.switchon.customer.data.QueuedException
import com.switchon.customer.data.Validation
import com.switchon.customer.data.formatMobile
import com.switchon.customer.ui.leaveFlowTo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.Catalog
import com.switchon.customer.data.DMY_SHORT
import com.switchon.customer.data.DeviceType
import com.switchon.customer.data.IssueType
import com.switchon.customer.data.dayName
import com.switchon.customer.data.rupees
import com.switchon.customer.ui.LocalToast
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.initialBookingStep
import com.switchon.customer.ui.showsCheckOffer
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.launch

private val stepNames = listOf("Device", "Issue", "Details", "Estimate", "Pickup", "Review")

@Composable
fun BookingScreen(vm: AppViewModel, nav: NavHostController) {
    val d = vm.draft
    var step by rememberSaveable { mutableStateOf(initialBookingStep(d)) }
    val reduced = LocalReducedMotion.current
    val toast = LocalToast.current
    var submitting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Never show a step whose earlier answers are missing (e.g. after the app was restored from the background).
    val maxStep = when { d.device == null -> 1; d.issue == null || d.estimate == null -> 2; else -> 6 }
    if (step > maxStep) step = maxStep

    fun back() { if (submitting) return; if (step > 1) step-- else nav.back() }
    BackHandler { back() }

    // After choosing the device, offer the optional device check once, before the issue step.
    val showCheckOffer = showsCheckOffer(d, step)

    val pickupProblem = vm.pickupProblem(d)
    val canContinue = when (step) {
        1 -> d.device != null
        2 -> d.issue != null
        3 -> Validation.notes(d.notes) == null
        4 -> d.estimate != null
        5 -> pickupProblem == null
        // Offline, the prod build queues the booking and sends it later; the demo build can only wait.
        else -> !vm.offline || vm.canQueue
    }

    fun confirm() {
        if (submitting) return
        submitting = true; failure = null
        scope.launch {
            vm.confirmBooking()
                .onSuccess { id -> nav.navigate(Routes.booked(id)) { popUpTo(Routes.BOOK) { inclusive = true } } }
                .onFailure {
                    when (it) {
                        is BusyException -> Unit
                        // Saved to the offline queue: it will be booked automatically, so leave the flow.
                        is QueuedException -> { toast(it.friendly()); nav.leaveFlowTo(Routes.HOME) }
                        else -> failure = it.friendly()
                    }
                }
            submitting = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Book a repair", ::back, eyebrow = "New repair") {
            SquareIconBtn(Icons.Outlined.Close, "Close booking", { if (!submitting) nav.back() })
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) { StepBar(step, 6, if (showCheckOffer) "Device check" else stepNames[step - 1]) }
        Rule(SO.line)
        AnimatedContent(
            step, Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 200)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) },
            label = "bookStep",
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (s) {
                    1 -> DeviceStep(vm) { step = 2 }
                    2 -> if (showCheckOffer) DeviceCheckOffer(vm, nav) else IssueStep(vm) { step = 3 }
                    3 -> DetailsStep(vm)
                    4 -> EstimateStep(vm)
                    5 -> PickupStep(vm, nav)
                    6 -> ReviewStep(vm, nav, failure, onEdit = { step = it }, onRetry = ::confirm)
                }
                Spacer(Modifier.heightIn(min = 12.dp))
            }
        }
        if (!showCheckOffer) BottomActions {
            if (step < 6) {
                Btn(
                    when (step) { 3 -> "See estimate"; 4 -> "Choose pickup"; 5 -> "Review booking"; else -> "Continue" },
                    { step++ }, Modifier.fillMaxWidth(), enabled = canContinue,
                )
                if (step == 3) Text("Brand, model and photos are optional. They help us narrow the estimate.", style = Type.small, color = SO.mute)
                if (step == 5 && pickupProblem != null) Text(pickupProblem, style = Type.small, color = SO.mute, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                Btn(if (vm.offline && vm.canQueue) "Save and send when online" else "Confirm booking", ::confirm, Modifier.fillMaxWidth(),
                    loading = submitting, enabled = !vm.offline || vm.canQueue)
                Text(
                    when {
                        vm.offline && vm.canQueue -> "You're offline. We'll book this automatically as soon as you're back online."
                        vm.offline -> "You're offline. Connect to confirm your booking."
                        else -> "Nothing to pay now. You pay after the repair is delivered."
                    },
                    style = Type.small, color = if (vm.offline) SO.redHot else SO.mute,
                )
            }
        }
    }
    LaunchedEffect(vm.offline) { if (vm.offline && step == 6) toast("You're offline. Your booking details are saved.") }
}

@Composable
private fun StepTitle(title: String, body: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Display(title, style = Type.h1)
        if (body != null) Text(body, style = Type.body, color = SO.mute)
    }
}

@Composable
private fun DeviceStep(vm: AppViewModel, next: () -> Unit) {
    StepTitle("What needs fixing?", "Pick the device. You'll see the base price before you book.")
    DeviceType.entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { dev ->
                Chip(
                    dev.label, vm.draft.device == dev,
                    onClick = {
                        vm.chooseDevice(dev)
                        next()
                    },
                    icon = deviceIcon(dev), tall = true, fillHeight = true, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun IssueStep(vm: AppViewModel, next: () -> Unit) {
    val device = vm.draft.device ?: return
    StepTitle("What's wrong with it?", "Choose the closest match. The repair shop confirms the exact fault.")
    val suggestion = vm.draft.diagnosticReport?.suggestedIssue
    if (vm.draft.issueFromCheck && suggestion != null && vm.draft.issue == suggestion.issue) Panel(border = SO.paper) {
        Text("SUGGESTED BY YOUR DEVICE CHECK", style = Type.label, color = SO.redHot)
        Text("${suggestion.reason}, so ${suggestion.issue.label.lowercase()} is pre-selected (${suggestion.confidence.name.lowercase()} confidence). It's not a diagnosis: change it if it doesn't match.",
            style = Type.body, color = SO.paper)
    }
    IssueType.entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { issue ->
                val price = Catalog.basePrice(device, issue)
                Chip(
                    Catalog.issueLabel(device, issue), vm.draft.issue == issue,
                    onClick = {
                        if (vm.draft.issue != issue) vm.draft = vm.draft.copy(issue = issue, symptoms = emptySet())
                        next()
                    },
                    icon = issueIcon(issue), tall = true, enabled = price != null,
                    sub = if (price == null) "Not offered for ${device.label.lowercase()}" else "From ${rupees(price)}",
                    fillHeight = true, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsStep(vm: AppViewModel) {
    val d = vm.draft
    val device = d.device ?: return
    val issue = d.issue ?: return
    val photos = d.photoUris
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        val cur = vm.draft.photoUris
        vm.draft = vm.draft.copy(photoUris = cur + uris.map(Uri::toString).filter { it !in cur }.take(4 - cur.size))
    }

    StepTitle("${device.label} · ${Catalog.issueLabel(device, issue)}", "Tell us more. All optional.")

    Section("Brand") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.brands[device].orEmpty().forEach { (b, _) ->
                Tag(b, d.brand == b) { vm.draft = d.copy(brand = if (d.brand == b) null else b, model = null) }
            }
        }
    }
    val models = Catalog.modelsFor(device, d.brand)
    if (models.isNotEmpty()) Section("Model") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { m -> Tag(m, d.model == m) { vm.draft = d.copy(model = if (d.model == m) null else m) } }
        }
    }
    Section("Symptoms") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.symptoms(issue).forEach { s ->
                Tag(s, s in d.symptoms) { vm.draft = d.copy(symptoms = if (s in d.symptoms) d.symptoms - s else d.symptoms + s) }
            }
        }
    }
    Field(
        "Notes for the technician", d.notes, { vm.draft = vm.draft.copy(notes = it) },
        placeholder = "When did it start? Anything already tried?", singleLine = false, minLines = 3, style = Type.body,
        maxLength = Validation.NOTES_MAX, capitalization = KeyboardCapitalization.Sentences, error = Validation.notes(d.notes),
    )
    Section("Photos · ${photos.size}/4") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEachIndexed { i, uri ->
                Box(Modifier.weight(1f).aspectRatio(1f).background(SO.ink3).border(1.dp, SO.line2).semantics(mergeDescendants = true) { contentDescription = "Photo ${i + 1} attached" }) {
                    Icon(Icons.Outlined.Image, null, tint = SO.mute, modifier = Modifier.align(Alignment.Center).size(28.dp))
                    Text("IMG %02d".format(i + 1), style = Type.label.copy(fontSize = 9.sp), color = SO.paper, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp))
                    SquareIconBtn(Icons.Outlined.Close, "Remove photo ${i + 1}", { vm.draft = vm.draft.copy(photoUris = vm.draft.photoUris - uri) }, Modifier.align(Alignment.TopEnd), border = SO.ink3)
                }
            }
            if (photos.size < 4) {
                Column(
                    Modifier.weight(1f).aspectRatio(1f).border(1.dp, SO.paper)
                        .clickable(role = Role.Button, onClickLabel = "Add photos") {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .semantics(mergeDescendants = true) { contentDescription = "Add photos, ${photos.size} of 4 attached" },
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.AddAPhoto, null, tint = SO.paper)
                    Text("ADD", style = Type.label, color = SO.paper)
                }
            }
            repeat((3 - photos.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** The estimate as a torn paper docket, matching the site's quote card. */
@Composable
fun EstimateDocket(
    device: DeviceType, issue: IssueType, brand: String?, model: String?, low: Int, high: Int, turnaround: String,
    showIncluded: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    Docket(Modifier.fillMaxWidth().scanLine(reduced, key = "$device$issue$brand$model")) {
        DocketHead("Estimate", "Pay after delivery")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(brand, model).joinToString(" ").ifBlank { device.label }.uppercase() + "\n" + Catalog.issueLabel(device, issue).uppercase(),
                style = Type.h3, color = SO.ink, modifier = Modifier.weight(1f),
            )
            Stamp("Base price", animate = false)
        }
        Column {
            Text("ESTIMATED BASE PRICE", style = Type.label, color = SO.pencil)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("FROM", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.pencil, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.width(8.dp))
                Text(rupees(low), style = Type.hero.copy(fontSize = 64.sp, lineHeight = 56.sp), color = SO.ink)
            }
            Text("up to ${rupees(high)}${if (brand == null) " depending on model" else ""}", style = Type.body, color = SO.pencil)
        }
        Row(Modifier.fillMaxWidth()) {
            DocketField("Turnaround", turnaround.uppercase(), Modifier.weight(1f), valueStyle = Type.h3)
            DocketField("Warranty", "90 DAYS", Modifier.weight(1f), valueStyle = Type.h3)
        }
        if (showIncluded) {
            Rule(SO.ink, 1.dp)
            Text("WHAT'S INCLUDED", style = Type.label, color = SO.pencil)
            Catalog.included(issue).forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("✓", style = Type.bodyStrong, color = SO.red, modifier = Modifier.width(22.dp))
                    Text(line, style = Type.body, color = SO.ink)
                }
            }
        }
        DashedRule()
        Row(verticalAlignment = Alignment.Top) {
            Tick(SO.red, 10.dp); Spacer(Modifier.width(10.dp))
            Column {
                Text("Final quote requires your approval.", style = Type.bodyStrong, color = SO.ink)
                Text("The shop diagnoses first and sends an itemised quote. No repair begins until you approve it.", style = Type.small, color = SO.pencil)
            }
        }
    }
}

@Composable
private fun EstimateStep(vm: AppViewModel) {
    val d = vm.draft
    val (low, high) = d.estimate ?: return
    StepTitle("Your estimate", "Transparent, published base price. GST included.")
    EstimateDocket(d.device!!, d.issue!!, d.brand, d.model, low, high, Catalog.turnaround(d.device, d.issue))
    Text("Pickup and drop are free. If it can't be fixed, or you decline the quote, we return it as it was and you pay nothing.", style = Type.body, color = SO.mute)
}

@Composable
private fun PickupStep(vm: AppViewModel, nav: NavHostController) {
    val d = vm.draft
    StepTitle("Pickup", "Where and when should the partner come?")
    Section("Address") {
        if (vm.addresses.isEmpty()) {
            EmptyState("No saved addresses", "Add an address", "We'll check doorstep pickup is available there.")
        }
        vm.addresses.forEach { a ->
            val ok = Catalog.isServiceable(a.pincode)
            val selected = d.addressId == a.id
            Row(
                Modifier.fillMaxWidth().background(if (selected) SO.ink3 else SO.ink2).border(if (selected) 2.dp else 1.dp, if (selected) SO.paper else SO.line2)
                    .selectable(selected, enabled = ok, role = Role.RadioButton) { vm.draft = d.copy(addressId = a.id) }
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 2.dp).size(18.dp).border(2.dp, if (selected) SO.red else SO.line2), contentAlignment = Alignment.Center) {
                    if (selected) Box(Modifier.size(8.dp).background(SO.red))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.label.uppercase(), style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper)
                        if (a.isDefault) { Spacer(Modifier.width(8.dp)); StatusTag("Default", SO.mute) }
                    }
                    Text(a.full, style = Type.body, color = if (ok) SO.paper else SO.mute)
                    if (a.landmark.isNotBlank()) Text(a.landmark, style = Type.small, color = SO.mute)
                    if (!ok) Text("Not serviceable yet", style = Type.small, color = SO.redHot)
                }
            }
        }
        Btn("Add new address", { nav.go(Routes.NEW_ADDRESS) }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = Icons.Outlined.Add, compact = true)
    }
    Section("Two-hour window") { SlotPicker(vm, d.slot) { vm.draft = vm.draft.copy(slot = it) } }
    Section("Contact at pickup") { ContactFields(vm) }
}

/**
 * Who the partner should ask for. The mobile field has an always-visible number pad, so it works even where
 * no keyboard reaches the app; typing still works when one does.
 */
@Composable
private fun ContactFields(vm: AppViewModel) {
    val d = vm.draft
    // Errors appear once a field has something in it, so an untouched form isn't shouting.
    val nameError = if (d.contactName.isNotEmpty()) Validation.name(d.contactName) else null
    val phoneError = if (d.contactPhone.isNotEmpty()) Validation.mobile(d.contactPhone) else null
    Field(
        "Name", d.contactName, { vm.draft = vm.draft.copy(contactName = it) },
        placeholder = "Priya Sharma", error = nameError, maxLength = Validation.NAME_MAX, capitalization = KeyboardCapitalization.Words,
    )
    Field(
        "Mobile number", d.contactPhone, { v -> vm.draft = vm.draft.copy(contactPhone = v.filter(Char::isDigit).take(10)) },
        placeholder = "98450 12873", prefix = "+91", keyboard = KeyboardType.Phone, error = phoneError,
        style = Type.monoL.copy(fontSize = 20.sp),
    )
    // Using the pad means the customer has left the name field: drop its focus (and keyboard).
    val focus = LocalFocusManager.current
    NumberPad(
        onDigit = { digit -> focus.clearFocus(); if (vm.draft.contactPhone.length < 10) vm.draft = vm.draft.copy(contactPhone = vm.draft.contactPhone + digit) },
        onDelete = { focus.clearFocus(); vm.draft = vm.draft.copy(contactPhone = vm.draft.contactPhone.dropLast(1)) },
        onClear = { focus.clearFocus(); vm.draft = vm.draft.copy(contactPhone = "") },
    )
    Text("Shared with the pickup partner on the day only. Calls go through a masked number.", style = Type.small, color = SO.mute)
}

@Composable
fun SlotPicker(vm: AppViewModel, selected: com.switchon.customer.data.Slot?, onPick: (com.switchon.customer.data.Slot) -> Unit) {
    // Availability changes as others book, so fetch fresh windows each time the picker appears.
    LaunchedEffect(Unit) { vm.refreshSlots() }
    val days = vm.slots()
    if (days.isEmpty() && vm.isBusy("slots")) { Text("LOADING PICKUP WINDOWS…", style = Type.label, color = SO.mute); return }
    var day by rememberSaveable { mutableStateOf(0) }
    val keys = days.keys.toList()
    if (keys.isEmpty()) { Text("No windows left. Try again tomorrow.", style = Type.body, color = SO.mute); return }
    val dayIdx = day.coerceIn(0, keys.lastIndex)
    Row(Modifier.fillMaxWidth().border(1.dp, SO.line2)) {
        keys.forEachIndexed { i, date ->
            val on = i == dayIdx
            Column(
                Modifier.weight(1f).heightIn(min = 60.dp).background(if (on) SO.paper else SO.ink)
                    .selectable(on, role = Role.Tab) { day = i }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(dayName(date).uppercase(), style = Type.label.copy(fontWeight = FontWeight.Bold), color = if (on) SO.ink else SO.mute)
                Text(date.format(DMY_SHORT).uppercase(), style = Type.h3.copy(fontSize = 20.sp), color = if (on) SO.ink else SO.paper)
            }
        }
    }
    days[keys[dayIdx]].orEmpty().chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { (slot, open) ->
                Chip(slot.window, selected == slot, { onPick(slot) }, enabled = open, sub = if (open) null else "Full", fillHeight = true, modifier = Modifier.weight(1f))
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReviewStep(vm: AppViewModel, nav: NavHostController, failure: String?, onEdit: (Int) -> Unit, onRetry: () -> Unit) {
    val d = vm.draft
    val (low, high) = d.estimate ?: return
    val address = vm.addresses.firstOrNull { it.id == d.addressId }
    StepTitle("Check and confirm")
    if (failure != null) ErrorPanel("Booking not confirmed", failure, onRetry)
    Docket(Modifier.fillMaxWidth()) {
        DocketHead("Repair summary", "Pay after delivery")
        ReviewRow("Device", listOfNotNull(d.device?.label, d.brand, d.model).joinToString(" · ")) { onEdit(1) }
        ReviewRow("Issue", Catalog.issueLabel(d.device!!, d.issue!!) + if (d.symptoms.isNotEmpty()) "\n" + d.symptoms.joinToString(", ") else "") { onEdit(2) }
        if (d.notes.isNotBlank() || d.photos > 0) ReviewRow("Notes", listOfNotNull(d.notes.takeIf { it.isNotBlank() }, if (d.photos > 0) "${d.photos} photo(s) attached" else null).joinToString("\n")) { onEdit(3) }
        ReviewRow("Pickup", "${d.slot?.label}\n${address?.full}") { onEdit(5) }
        ReviewRow("Contact", "${d.contactName.trim()} · ${formatMobile(d.contactPhone)}") { onEdit(5) }
        d.diagnosticReport?.let { report -> AttachedCheckSummary(report, onView = { nav.go(Routes.diagnostics(CheckOrigin.REVIEW)) }) }
        Rule(SO.ink, 1.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("BASE ESTIMATE", style = Type.label, color = SO.pencil)
                Text(rupees(low), style = Type.price.copy(fontSize = 44.sp, lineHeight = 42.sp), color = SO.ink)
                Text("to ${rupees(high)} · ${Catalog.turnaround(d.device, d.issue)}", style = Type.small, color = SO.pencil)
            }
            Stamp("Pay later", animate = false)
        }
        DashedRule()
        Text(
            "Due now: ₹0. You approve the final quote before any work starts, and pay by UPI, card or cash after delivery. Cancel free until the partner sets off.",
            style = Type.small, color = SO.pencil,
        )
    }
}

@Composable
private fun ReviewRow(label: String, value: String, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        DocketField(label, value, Modifier.weight(1f))
        Box(
            Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Edit $label", onClick = onEdit)
                .semantics { contentDescription = "Edit $label" }.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("EDIT", style = Type.label.copy(fontWeight = FontWeight.Bold, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = SO.red)
        }
    }
}

/** Confirmation: the stamp, the repair ID, and what happens next. */
@Composable
fun BookedScreen(vm: AppViewModel, nav: NavHostController, id: String?) {
    val r = vm.repair(id) ?: return MissingRepair(nav)
    ScreenScaffold(
        "Booking confirmed", null, eyebrow = "Repair booked",
        bottom = {
            Btn("Track repair", { nav.go(Routes.track(r.id)) { popUpTo(Routes.HOME) } }, Modifier.fillMaxWidth())
            Btn("Back to home", { nav.leaveFlowTo(Routes.HOME) }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Stamp(if (r.warrantyClaimFor != null) "Claim raised" else "Pickup scheduled", big = true, rotation = -6f, color = SO.redHot)
        }
        Docket(Modifier.fillMaxWidth()) {
            DocketHead("Repair ID", "Keep for reference")
            Text(r.id, style = Type.hero.copy(fontFamily = com.switchon.customer.ui.theme.Mono, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = 1.sp), color = SO.ink)
            DocketField("Device", "${r.title} · ${r.jobTitle}")
            DocketField("Pickup window", r.slot.label)
            DocketField("Address", r.address.full)
            if (r.contactPhone.isNotEmpty()) DocketField("Contact", "${r.contactName} · ${formatMobile(r.contactPhone)}")
            Rule(SO.ink, 1.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                if (r.warrantyClaimFor != null) DocketField("Cost", "₹0 · COVERED", Modifier.weight(1f), valueStyle = Type.h3)
                else Column(Modifier.weight(1f)) {
                    Text("BASE ESTIMATE", style = Type.label, color = SO.pencil)
                    Price(r.estimateLow, lead = "From", color = SO.ink, leadColor = SO.pencil)
                }
                Text("DUE NOW ₹0", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.red)
            }
        }
        Section("What happens next") {
            listOf(
                "01" to "We assign a verified pickup partner and text you their name and vehicle.",
                "02" to "They check your OTP, photograph the device and seal it in front of you.",
                "03" to "The shop diagnoses it and sends an itemised quote here for your approval.",
            ).forEach { (n, t) ->
                Row {
                    Text(n, style = Type.h3, color = SO.redHot, modifier = Modifier.width(40.dp))
                    Text(t, style = Type.body, color = SO.paper)
                }
            }
        }
    }
}

@Composable
fun MissingRepair(nav: NavHostController) {
    ScreenScaffold("Not found", { nav.back() }) {
        EmptyState("Error 404", "Repair not found", "This repair may have been removed. Check your repairs list.") {
            Btn("Go to repairs", { nav.leaveFlowTo(Routes.REPAIRS) }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE)
        }
    }
}
