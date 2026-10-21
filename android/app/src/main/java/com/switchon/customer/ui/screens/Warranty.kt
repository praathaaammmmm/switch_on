package com.switchon.customer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.BusyException
import com.switchon.customer.data.DMY
import com.switchon.customer.data.Repair
import com.switchon.customer.data.Slot
import com.switchon.customer.data.WARRANTY_DAYS
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.launch

private val covered = listOf("The same fault returning", "A faulty replacement part", "Free pickup and drop for claims")
private val notCovered = listOf("New drops, cracks or liquid damage", "Devices opened by another shop", "Faults unrelated to the repair")

@Composable
fun WarrantyScreen(vm: AppViewModel, nav: NavHostController, id: String?) {
    val r = vm.repair(id)
    ScreenScaffold(if (r != null) "Warranty" else "90-day warranty", { nav.back() }, eyebrow = r?.id ?: "Cover") {
        if (r != null) RepairCover(vm, r, nav)
        else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("90", style = Type.hero.copy(fontSize = 150.sp, lineHeight = 120.sp), color = SO.redHot)
                Spacer(Modifier.width(10.dp))
                Display("Days on\nevery repair", style = Type.h2, modifier = Modifier.padding(bottom = 14.dp))
            }
            Text("Starts the day your repaired device is delivered. Parts and labour both covered.", style = Type.bodyL, color = SO.mute)
        }
        CoverLists()
        if (r == null) {
            Section("Your cover") {
                val withCover = vm.completed.filter { !it.declined }
                if (withCover.isEmpty()) EmptyState("No covered repairs", "Nothing covered yet", "Completed repairs appear here with their cover dates.")
                withCover.forEach { c ->
                    val block = vm.claimBlocker(c)
                    Panel(border = if (block == null) SO.paper else SO.line2) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.id, style = Type.mono, color = SO.mute)
                                Text("${c.title} · ${c.jobTitle}".uppercase(), style = Type.h3, color = SO.paper)
                            }
                            StatusTag(if (c.warrantyActive()) "${c.warrantyDaysLeft()} days left" else "Expired", if (c.warrantyActive()) SO.paper else SO.mute)
                        }
                        CoverBar(c)
                        Btn(
                            if (block == null) "Raise a warranty claim" else "View cover",
                            { nav.go(if (block == null) Routes.claim(c.id) else Routes.warranty(c.id)) },
                            Modifier.fillMaxWidth(), kind = if (block == null) BtnKind.PRIMARY else BtnKind.OUTLINE, compact = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairCover(vm: AppViewModel, r: Repair, nav: NavHostController) {
    val block = vm.claimBlocker(r)
    Docket(Modifier.fillMaxWidth()) {
        DocketHead("90-day cover", r.id)
        Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.h3, color = SO.ink)
        if (r.warrantyActive()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${r.warrantyDaysLeft()}", style = Type.hero.copy(fontSize = 84.sp, lineHeight = 74.sp), color = SO.red)
                Spacer(Modifier.width(8.dp))
                Text("DAYS\nLEFT", style = Type.h3, color = SO.ink, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Stamp("Covered", animate = true)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.declined) "NOT COVERED" else "EXPIRED", style = Type.h1, color = SO.ink, modifier = Modifier.weight(1f))
                Stamp(if (r.declined) "Unrepaired" else "Expired", color = SO.pencil)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            DocketField("Delivered", r.deliveredAt?.toLocalDate()?.format(DMY) ?: "—", Modifier.weight(1f))
            DocketField("Covered until", r.warrantyUntil?.format(DMY) ?: "—", Modifier.weight(1f))
        }
        DocketField("What's covered", r.quote?.warranty ?: "Parts and labour")
        DocketField("Repaired by", r.shop?.let { "${it.name} · ${it.area}" } ?: "—")
    }
    if (block == null) {
        Btn("Raise a warranty claim", { nav.go(Routes.claim(r.id)) }, Modifier.fillMaxWidth())
        Text("Free pickup and drop. If the shop finds the fault is covered, there's nothing to pay.", style = Type.small, color = SO.mute)
    } else {
        Btn("Raise a warranty claim", {}, Modifier.fillMaxWidth(), enabled = false)
        Text(block, style = Type.body, color = SO.mute)
        Btn("Talk to support instead", { nav.go(Routes.report(r.id)) }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE)
    }
}

@Composable
private fun CoverBar(r: Repair) {
    val left = r.warrantyDaysLeft().toFloat() / WARRANTY_DAYS
    Row(Modifier.fillMaxWidth().height(6.dp).background(SO.line)) {
        if (left > 0f) Box(Modifier.weight(left.coerceAtLeast(0.01f)).height(6.dp).background(SO.red))
        if (left < 1f) Spacer(Modifier.weight((1f - left).coerceAtLeast(0.01f)))
    }
}

@Composable
private fun CoverLists() {
    Column(Modifier.fillMaxWidth()) {
        Rule(SO.paper, 2.dp)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("COVERED", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper)
                covered.forEach { Row { Text("✓ ", style = Type.bodyStrong, color = SO.redHot); Text(it, style = Type.body, color = SO.paper) } }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NOT COVERED", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.mute)
                notCovered.forEach { Row { Text("✕ ", style = Type.bodyStrong, color = SO.mute); Text(it, style = Type.body, color = SO.mute) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClaimScreen(vm: AppViewModel, nav: NavHostController, id: String?) {
    val r = vm.repair(id) ?: return MissingRepair(nav)
    val block = vm.claimBlocker(r)
    val problems = listOf("Same fault is back", "Replaced part stopped working", "New part looks faulty", "Something else")
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf("") }
    var addressId by rememberSaveable { mutableStateOf(r.address.id.takeIf { a -> vm.addresses.any { it.id == a } } ?: vm.defaultAddress?.id) }
    var slot by remember { mutableStateOf<Slot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val ready = block == null && problem != null && slot != null && addressId != null && !vm.offline

    ScreenScaffold(
        "Warranty claim", { nav.back() }, eyebrow = "For ${r.id}",
        bottom = {
            Btn(
                "Raise claim · free pickup",
                {
                    if (busy) return@Btn
                    busy = true; failure = null
                    scope.launch {
                        vm.raiseClaim(r.id, problem!!, detail, slot!!, addressId!!)
                            .onSuccess { newId -> nav.go(Routes.booked(newId)) { popUpTo(Routes.HOME) } }
                            .onFailure { if (it !is BusyException) failure = it.friendly() }
                        busy = false
                    }
                },
                Modifier.fillMaxWidth(), enabled = ready, loading = busy,
            )
        },
    ) {
        if (block != null) { ErrorPanel("Not eligible", block, onRetry = null); return@ScreenScaffold }
        Docket(Modifier.fillMaxWidth(), torn = false) {
            DocketHead("Claim against", r.id)
            Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.h3, color = SO.ink)
            Row(Modifier.fillMaxWidth()) {
                DocketField("Cover until", r.warrantyUntil?.format(DMY) ?: "—", Modifier.weight(1f))
                DocketField("Cost to you", "₹0 if covered", Modifier.weight(1f))
            }
        }
        failure?.let { ErrorPanel("Claim not sent", it, onRetry = null) }
        Section("What's wrong?") {
            problems.forEach { p -> Chip(p, problem == p, { problem = p }, Modifier.fillMaxWidth()) }
        }
        Field("Describe it", detail, { detail = it.take(300) }, placeholder = "e.g. Touch stopped working again near the bottom", singleLine = false, minLines = 3, style = Type.body)
        Section("Pickup from") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.addresses.forEach { a -> Tag("${a.label} · ${a.area}", addressId == a.id) { addressId = a.id } }
            }
        }
        Section("Two-hour window") { SlotPicker(vm, slot) { slot = it } }
        Text("The shop checks the fault first. If it turns out not to be covered (say, a new crack), you'll get a normal quote and can decline it free.", style = Type.small, color = SO.mute)
    }
}
