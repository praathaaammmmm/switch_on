package com.switchon.customer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.remote.stageFor
import com.switchon.customer.data.BusyException
import com.switchon.customer.data.NotAllowedException
import com.switchon.customer.data.AuditEvent
import com.switchon.customer.data.EventKind
import com.switchon.customer.data.Repair
import com.switchon.customer.data.Slot
import com.switchon.customer.data.Stage
import com.switchon.customer.data.rupees
import com.switchon.customer.data.stamp
import com.switchon.customer.ui.LocalToast
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Audit titles that mark each stage, so timeline times come straight from the custody log. */
private val stageEvents = mapOf(
    Stage.PICKUP_SCHEDULED to listOf("Repair booked", "Warranty claim raised"),
    Stage.PARTNER_ASSIGNED to listOf("Partner assigned"),
    Stage.PICKED_UP to listOf("Device sealed and picked up"),
    Stage.AT_SHOP to listOf("Handed to repair shop"),
    Stage.DIAGNOSED to listOf("Diagnosis complete"),
    Stage.QUOTE_PENDING to listOf("Quote sent"),
    Stage.REPAIRING to listOf("Repair started", "Quote approved"),
    Stage.QUALITY_CHECK to listOf("Repair finished"),
    Stage.RETURNING to listOf("Out for delivery", "Out for return", "Resealed for return"),
    Stage.DELIVERED to listOf("Delivered", "Returned to you"),
)

private fun eventFor(r: Repair, s: Stage): AuditEvent? =
    // Server events carry the status they moved into; demo events are matched by title.
    r.audit.firstOrNull { it.status != null && stageFor(it.status) == s }
        ?: stageEvents[s].orEmpty().firstNotNullOfOrNull { t -> r.audit.firstOrNull { it.title.startsWith(t) } }

@Composable
fun TrackerScreen(vm: AppViewModel, nav: NavHostController, id: String?) {
    val r = vm.repair(id) ?: return MissingRepair(nav)
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var loading by rememberSaveable(id) { mutableStateOf(true) }
    var showCancel by remember { mutableStateOf(false) }
    var showReschedule by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Show the cached copy straight away if we have one; fetch the latest status either way.
    LaunchedEffect(id) {
        if (r.serverStatus != null) loading = false
        val started = System.currentTimeMillis()
        vm.refreshRepair(r.id)
        delay((450 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
        loading = false
    }

    fun dial(number: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.replace(" ", "")))) }

    ScreenScaffold(
        r.id, { nav.back() }, eyebrow = "Repair tracker",
        bottom = when {
            r.stage == Stage.QUOTE_PENDING && r.isActive -> ({
                Btn("Review quote · ${rupees(r.quote?.total ?: 0)}", { nav.go(Routes.quote(r.id)) }, Modifier.fillMaxWidth())
            })
            r.needsPayment -> ({ Btn("Pay ${rupees(r.amountDue)}", { nav.go(Routes.pay(r.id)) }, Modifier.fillMaxWidth()) })
            else -> null
        },
    ) {
        if (loading) { LoadingList("Fetching live status", rows = 2); return@ScreenScaffold }

        StatusHeader(r)
        val (alert, urgent) = alertFor(r)
        if (alert.isNotBlank() && r.isActive) AlertStrip(alert, urgent)

        if (vm.canAdvance && r.isActive && r.stage < Stage.DELIVERED && r.stage != Stage.QUOTE_PENDING) {
            PrototypeAdvance {
                scope.launch { vm.advance(r.id).onFailure { if (it !is BusyException) toast(it.message ?: "Couldn't move this repair on.") } }
            }
        }

        if (!r.isCancelled) Section("Status") { Timeline(r) }

        // A device check attached when this was booked on this phone. It isn't part of the server's record.
        vm.savedReport(r.id)?.let { report ->
            Section("Device check") {
                Docket(Modifier.fillMaxWidth()) {
                    AttachedCheckSummary(report, onView = { nav.go(Routes.savedCheck(r.id)) },
                        saved = "Kept on this phone only, not sent with the booking. Show it to the technician at pickup if it helps. It never changes your quote.")
                }
            }
        }

        // People and place
        Section("Your slot") {
            Panel {
                KeyValue("Pickup", r.slot.label)
                KeyValue("Address", r.address.full)
                if (r.canCancel) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Reschedule", { showReschedule = true }, Modifier.weight(1f), kind = BtnKind.OUTLINE, compact = true, icon = null)
                    Btn("Cancel", { showCancel = true }, Modifier.weight(1f), kind = BtnKind.OUTLINE, compact = true, icon = null)
                }
            }
        }
        r.courier?.let { c ->
            Section("Pickup partner") {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.uppercase(), style = Type.h3, color = SO.paper)
                            Text(c.vehicle, style = Type.mono, color = SO.mute)
                            Text("★ ${c.rating} · ${"%,d".format(c.trips)} pickups · ID verified", style = Type.small, color = SO.mute)
                        }
                        SquareIconBtn(Icons.Outlined.Call, "Call ${c.name}", { dial(c.phone) }, tint = SO.paper, border = SO.paper)
                    }
                }
            }
        }
        r.shop?.let { s ->
            Section("Repair shop") {
                Panel(border = SO.paper) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Verified, null, tint = SO.redHot, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("VERIFIED PARTNER", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.redHot)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.Star, null, tint = SO.paper, modifier = Modifier.size(16.dp))
                        Text(" ${s.rating}", style = Type.mono, color = SO.paper)
                        Text(" (${"%,d".format(s.reviews)})", style = Type.small, color = SO.mute)
                    }
                    Text(s.name.uppercase(), style = Type.h2, color = SO.paper)
                    Text(s.area, style = Type.body, color = SO.mute)
                    Rule(SO.line)
                    KeyValue("Technician", s.technician)
                    KeyValue("Verified", "Since ${s.verifiedSince} · KYC, GST and bench audit")
                }
            }
        }
        r.sealCode?.let { code ->
            Section("Sealed-device code") {
                Row(
                    Modifier.fillMaxWidth().border(2.dp, SO.paper).padding(14.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Inventory2, null, tint = SO.redHot, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SEAL", style = Type.label, color = SO.mute)
                        Text(code, style = Type.monoL.copy(fontSize = 24.sp), color = SO.paper)
                    }
                }
                Text("Match this code on the bag at every handover. If the seal is broken or the code differs, don't accept it and tap Get help.", style = Type.small, color = SO.mute)
            }
        }
        if (r.photos.isNotEmpty()) Section("Handover photos") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.photos.forEach { p ->
                    HandoverTile(p.caption, stamp(p.at).uppercase(), if (p.caption.startsWith("Sealed")) Icons.Outlined.Inventory2 else deviceIcon(r.device), Modifier.weight(1f))
                }
            }
        }

        Section("Status log") { AuditTrail(r.audit) }

        Section("Need help?") {
            Btn("Get help with ${r.id}", { nav.go(Routes.report(r.id)) }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER)
            Btn("Chat with support", { nav.go(Routes.CHAT) }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE)
        }
    }

    if (showCancel) {
        val reasons = listOf("Booked by mistake", "Fixed it another way", "Pickup time doesn't work", "Price too high")
        var reason by remember { mutableStateOf(reasons[0]) }
        SoDialog(
            "Cancel pickup?", "Free to cancel until the partner sets off. Nothing has been charged.",
            confirm = "Cancel repair", danger = true, dismiss = "Keep booking", loading = busy,
            onConfirm = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        vm.cancel(r.id, reason).onSuccess { showCancel = false; toast("Repair cancelled. No charge.") }
                            .onFailure { if (it !is BusyException) { toast(it.friendly()); if (it is NotAllowedException) showCancel = false } }
                        busy = false
                    }
                }
            },
            onDismiss = { showCancel = false },
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    reasons.forEach { Chip(it, reason == it, { reason = it }, Modifier.fillMaxWidth()) }
                }
            },
        )
    }
    if (showReschedule) {
        var slot by remember { mutableStateOf<Slot?>(null) }
        SoDialog(
            "New pickup window", "Pick a two-hour window. Your partner is told straight away.",
            confirm = "Save window", loading = busy, dismiss = "Keep ${r.slot.window}",
            onConfirm = {
                val s = slot ?: return@SoDialog toast("Choose a window first")
                if (!busy) {
                    busy = true
                    scope.launch {
                        vm.reschedule(r.id, s).onSuccess { showReschedule = false; toast("Pickup moved to ${s.label}") }
                            .onFailure { if (it !is BusyException) { toast(it.friendly()); if (it is NotAllowedException) showReschedule = false } }
                        busy = false
                    }
                }
            },
            onDismiss = { showReschedule = false },
            extra = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SlotPicker(vm, slot) { slot = it } } },
        )
    }
}

@Composable
private fun StatusHeader(r: Repair) {
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxWidth().scanLine(reduced, key = r.stage), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.label, color = SO.mute)
                Display(
                    when {
                        r.isCancelled -> "Cancelled"
                        r.isDelivered && r.declined -> "Returned"
                        else -> r.stage.label
                    },
                    style = Type.h1,
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("ETA ", style = Type.label, color = SO.mute)
            Text((if (r.isCancelled) "—" else etaFor(r)).uppercase(), style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper, modifier = Modifier.weight(1f))
        }
        // Stamps only where they add to the title: a decision made, or the job closed.
        val stampText = when {
            r.isCancelled -> "Cancelled · no charge"
            r.isDelivered && r.declined -> "Returned unrepaired"
            r.isDelivered -> "Repair complete"
            r.quote?.decision == com.switchon.customer.data.Decision.APPROVED -> "Quote approved"
            r.declined -> "Quote declined"
            else -> null
        }
        if (stampText != null) Stamp(stampText, Modifier.padding(vertical = 4.dp), color = if (r.isCancelled) SO.mute else SO.red)
        if (!r.isCancelled) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Stage.entries.forEach { s -> Box(Modifier.weight(1f).height(8.dp).background(if (s <= r.stage) SO.red else SO.line)) }
        }
    }
}

@Composable
private fun AlertStrip(text: String, urgent: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(if (urgent) SO.red else SO.paper).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(if (urgent) SO.paper else SO.red))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.bodyStrong, color = if (urgent) SO.paper else SO.ink)
    }
}

/** Prototype stand-in for server pushes: move the order on one step. Clearly labelled as such. */
@Composable
private fun PrototypeAdvance(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, SO.line).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PROTOTYPE · LIVE UPDATES", style = Type.label, color = SO.mute2, modifier = Modifier.weight(1f))
        TextAction("Next update", onClick, SO.paper, Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun Timeline(r: Repair) {
    val reduced = LocalReducedMotion.current
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseA")
    Column {
        Stage.entries.forEachIndexed { i, s ->
            val skipped = r.declined && (s == Stage.REPAIRING || s == Stage.QUALITY_CHECK)
            val done = !r.isCancelled && s < r.stage || (r.isDelivered && s == Stage.DELIVERED)
            val current = !r.isCancelled && s == r.stage && !r.isDelivered
            val ev = eventFor(r, s)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {
                contentDescription = "${s.label}: " + when { skipped -> "skipped"; done -> "done"; current -> "current step"; else -> "not yet" }
            }) {
                Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.padding(top = 2.dp).size(18.dp)
                            .graphicsLayer { alpha = if (current && !reduced) a else 1f }
                            .background(when { skipped -> SO.ink; done -> SO.red; current -> SO.paper; else -> SO.ink })
                            .border(2.dp, when { skipped -> SO.line2; done -> SO.red; current -> SO.red; else -> SO.line2 }),
                        contentAlignment = Alignment.Center,
                    ) { if (done && !skipped) Text("✓", style = Type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = SO.paper) }
                    if (i < Stage.entries.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(if (done && !skipped) SO.red else SO.line))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = 18.dp)) {
                    Text(
                        s.label,
                        style = if (current) Type.h3 else Type.bodyStrong,
                        color = when { skipped -> SO.mute2; done || current -> SO.paper; else -> SO.mute },
                    )
                    val meta = when {
                        skipped -> "Skipped · you declined the quote"
                        ev != null && (done || current) -> stamp(ev.at) + " · " + ev.actor
                        current -> "In progress"
                        else -> null
                    }
                    if (meta != null) Text(meta.uppercase(), style = Type.label.copy(letterSpacing = 0.8.sp), color = SO.mute)
                }
            }
        }
    }
}

/** Every custody and status event, newest first, with who did it. */
@Composable
fun AuditTrail(events: List<AuditEvent>) {
    Column(Modifier.fillMaxWidth().border(1.dp, SO.line2)) {
        // Newest first; events logged at the same minute keep their logged order, reversed.
        events.withIndex().sortedWith(compareByDescending<IndexedValue<AuditEvent>> { it.value.at }.thenByDescending { it.index })
            .map { it.value }.forEachIndexed { i, e ->
            if (i > 0) Rule(SO.line)
            Row(Modifier.fillMaxWidth().padding(12.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                val kind = when (e.kind) {
                    EventKind.CUSTODY -> "CUSTODY"; EventKind.PAYMENT -> "PAYMENT"; EventKind.ALERT -> "ACTION"
                    EventKind.NOTE -> "NOTE"; EventKind.STATUS -> "STATUS"
                }
                Column(Modifier.width(78.dp)) {
                    Text(kind, style = Type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = if (e.kind == EventKind.CUSTODY || e.kind == EventKind.ALERT) SO.redHot else SO.mute)
                }
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = Type.bodyStrong, color = SO.paper)
                    if (e.detail.isNotBlank()) Text(e.detail, style = Type.small, color = SO.mute)
                    Text("${stamp(e.at)} · ${e.actor}".uppercase(), style = Type.label.copy(fontSize = 10.sp, letterSpacing = 0.6.sp), color = SO.mute2)
                }
            }
        }
    }
}
