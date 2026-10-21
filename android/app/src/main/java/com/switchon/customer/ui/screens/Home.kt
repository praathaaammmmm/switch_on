package com.switchon.customer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.switchon.customer.ui.components.TextAction
import com.switchon.customer.data.stamp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.Repair
import com.switchon.customer.data.Stage
import com.switchon.customer.data.rupees
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.HeroAction
import com.switchon.customer.ui.heroRoute
import com.switchon.customer.ui.switchTab
import com.switchon.customer.ui.components.Stamp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import com.switchon.customer.ui.components.Btn
import com.switchon.customer.ui.components.BtnKind
import com.switchon.customer.ui.components.Display
import com.switchon.customer.ui.components.Eyebrow
import com.switchon.customer.ui.components.LocalReducedMotion
import com.switchon.customer.ui.components.OctoFace
import com.switchon.customer.ui.components.OctoHero
import com.switchon.customer.ui.components.Rule
import com.switchon.customer.ui.components.StatusTag
import com.switchon.customer.ui.components.Tick
import com.switchon.customer.ui.components.scanLine
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import java.time.LocalTime

@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val first = vm.profile.name.substringBefore(' ')
    val hour = LocalTime.now().hour
    val greeting = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Masthead
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo()
            Spacer(Modifier.width(12.dp))
            Text((if (first.isBlank()) greeting else "$greeting, $first").uppercase(), style = Type.label, color = SO.mute, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Rule()

        Hero(
            onStart = { nav.go(vm.heroRoute(HeroAction.START_REPAIR)) },
            onCheck = { nav.go(vm.heroRoute(HeroAction.DEVICE_CHECK)) },
        )

        // Bookings or reports saved while offline, waiting for a connection (or refused by the server).
        if (vm.pending.isNotEmpty()) PendingPanel(vm)

        // Active repairs sit right under the hero: they're what a returning customer opened the app for.
        val active = vm.active
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (active.isNotEmpty()) {
                Eyebrow(if (active.size == 1) "Your repair" else "Your repairs · ${active.size}")
                active.forEach { r -> ActiveRepairCard(r, nav) }
            }
            HubLink(if (active.isEmpty()) "Devices, popular services and how it works" else "All your repairs, devices and prices") { nav.switchTab(Routes.REPAIRS) }
        }
    }
}

/**
 * Compact welcome: headline, the octopus technician, both entry points and the trust strip. Short enough that an
 * active repair card still shows on the first screen of a standard phone.
 */
@Composable
private fun Hero(onStart: () -> Unit, onCheck: () -> Unit) {
    val reduced = LocalReducedMotion.current
    // Large text still grows, but the poster headline stops at 1.15x so the hero stays compact.
    val scale = LocalDensity.current.fontScale
    val headline = Type.h1.copy(fontSize = (40f * minOf(1f, 1.15f / scale)).sp, lineHeight = (38f * minOf(1f, 1.15f / scale)).sp)
    Column(Modifier.fillMaxWidth().background(SO.ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f).scanLine(reduced, durationMs = 1000).semantics(mergeDescendants = true) { heading() },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("DOORSTEP REPAIR", style = Type.label, color = SO.mute)
                Column {
                    Display("Know the fault.", style = headline)
                    Display("Know the price.", style = headline, color = SO.redHot)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box {
                OctoHero(Modifier.width(116.dp), height = 148.dp, tag = "On call")
                Stamp("Verified", Modifier.align(Alignment.BottomStart).offset(x = (-8).dp, y = 10.dp), rotation = -7f, fill = SO.paper)
            }
        }
        Text("Doorstep device repair, tracked from pickup to return.", style = Type.bodyL, color = SO.paper)
        Btn("Start a repair", onStart, Modifier.fillMaxWidth())
        Btn("Run device check", onCheck, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
        TrustStrip()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrustStrip() {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Verified shops. Pay after repair. 90-day cover." }) {
        Rule(SO.line2)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Verified shops", "Pay after repair", "90-day cover").forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tick(SO.redHot, 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(t.uppercase(), style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper)
                }
            }
        }
        Rule(SO.line2)
    }
}

/** One line into the Repairs hub, where discovery and history live. */
@Composable
private fun HubLink(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, SO.line2)
            .clickable(role = Role.Button, onClickLabel = "Open the Repairs hub", onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("REPAIRS HUB", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper)
            Text(text, style = Type.small, color = SO.mute)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = SO.paper, modifier = Modifier.size(20.dp))
    }
}

/** What to tell the customer about an order right now, in one line. */
fun alertFor(r: Repair): Pair<String, Boolean> = when {
    r.pickupFailed -> "We missed your pickup. Choose a new window, or cancel for free." to true
    r.notPossible && r.stage == Stage.RETURNING -> "The shop couldn't repair it. It's on its way back, nothing to pay." to false
    r.stage == Stage.QUOTE_PENDING -> "Quote ready: ${rupees(r.quote?.total ?: 0)}. Approve it to start the repair." to true
    r.declined && r.stage == Stage.RETURNING -> "Quote declined. Your device is on its way back, nothing to pay." to false
    r.stage == Stage.PICKUP_SCHEDULED -> "Pickup ${r.slot.inlineLabel}. Keep the device switched off and backed up." to false
    r.stage == Stage.PARTNER_ASSIGNED -> "${r.courier?.name} will arrive in your ${r.slot.window} window." to false
    r.stage == Stage.PICKED_UP -> "Sealed with ${r.sealCode}. Heading to ${r.shop?.name ?: "the repair shop"}." to false
    r.stage == Stage.AT_SHOP -> "At ${r.shop?.name}. Seal checked on arrival. Diagnosis under way." to false
    r.stage == Stage.DIAGNOSED -> "Diagnosis done. Your itemised quote is being prepared." to false
    r.stage == Stage.REPAIRING -> "Being repaired by ${r.shop?.technician?.substringBefore(" ·")?.trimEnd('.')}." to false
    r.stage == Stage.QUALITY_CHECK -> "Repair done. Running the full function test." to false
    r.stage == Stage.RETURNING -> "Out for delivery today. Pay ${rupees(r.amountDue)} after you've checked it." to false
    else -> "" to false
}

fun etaFor(r: Repair): String = when (r.stage) {
    Stage.PICKUP_SCHEDULED, Stage.PARTNER_ASSIGNED -> "Pickup ${r.slot.dayLabel.lowercase()}, ${r.slot.window}"
    Stage.PICKED_UP, Stage.AT_SHOP, Stage.DIAGNOSED -> "Quote within 4 hours"
    Stage.QUOTE_PENDING -> "Waiting for you"
    Stage.REPAIRING, Stage.QUALITY_CHECK -> "Back in ${r.turnaround}"
    Stage.RETURNING -> "Arriving today, 5 – 7 PM"
    Stage.DELIVERED -> "Delivered"
}

@Composable
private fun ActiveRepairCard(r: Repair, nav: NavHostController) {
    val (alert, urgent) = alertFor(r)
    Column(
        Modifier.fillMaxWidth().background(SO.ink2).border(2.dp, if (urgent) SO.red else SO.paper)
            .clickable(role = Role.Button, onClickLabel = "Track ${r.id}") { nav.go(Routes.track(r.id)) },
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            OctoFace(44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("REPAIR ID", style = Type.label, color = SO.mute)
                Text(r.id, style = Type.monoL, color = SO.paper, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = SO.mute, modifier = Modifier.size(20.dp))
        }
        Rule(SO.line2)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusTag(r.stage.label, color = SO.paper, fill = if (urgent) SO.red else SO.ink2)
            Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.h3, color = SO.paper)
            // Ten-segment progress, red to the current step.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Stage.entries.forEach { s ->
                    Box(Modifier.weight(1f).height(6.dp).background(if (s <= r.stage) SO.red else SO.line))
                }
            }
            Row {
                Text("ETA ", style = Type.label, color = SO.mute)
                Text(etaFor(r).uppercase(), style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper)
            }
            Text(alert, style = Type.body, color = if (urgent) SO.paper else SO.mute)
            if (urgent) Btn("Review quote", { nav.go(Routes.quote(r.id)) }, Modifier.fillMaxWidth(), compact = true)
            else Btn("Track repair", { nav.go(Routes.track(r.id)) }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, compact = true)
        }
    }
}

@Composable
private fun PendingPanel(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Waiting to send · ${vm.pending.size}")
        vm.pending.forEach { p ->
            Column(
                Modifier.fillMaxWidth().background(SO.ink2).border(1.dp, if (p.error != null) SO.redHot else SO.line2).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(p.title.uppercase(), style = Type.h3.copy(fontSize = 20.sp), color = SO.paper)
                if (p.error == null) {
                    Text("Saved ${stamp(p.createdAt)}. It will be sent automatically when you're back online.", style = Type.body, color = SO.mute)
                } else {
                    Text("Couldn't be sent: ${p.error}", style = Type.body, color = SO.redHot)
                    TextAction("Dismiss", { scope.launch { vm.dismissPending(p.requestId) } })
                }
            }
        }
    }
}
