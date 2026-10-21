package com.switchon.customer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.heading
import com.switchon.customer.data.Catalog
import com.switchon.customer.data.DeviceType
import com.switchon.customer.ui.BookingEntry
import com.switchon.customer.ui.PopularService
import com.switchon.customer.ui.begin
import com.switchon.customer.ui.popularServices
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.DMY
import com.switchon.customer.data.DMY_SHORT
import com.switchon.customer.data.Repair
import com.switchon.customer.data.Stage
import com.switchon.customer.data.rupees
import com.switchon.customer.data.stamp
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.delay

/** The hub's top-level sections, in page order. */
private enum class HubSection(val title: String, val short: String) {
    MINE("My repairs", "My repairs"),
    DEVICES("Repair a device", "Devices"),
    POPULAR("Popular services", "Popular"),
    HOW("How it works", "How it works"),
}

/**
 * The Repairs hub: the customer's repairs (active, completed, cancelled) and everything for starting a new one,
 * on one page. Every device and service opens the same booking flow with its choices prefilled.
 */
@Composable
fun RepairsScreen(vm: AppViewModel, nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    var attempt by remember { mutableIntStateOf(0) }
    // Refresh on open, on Retry, and when the connection comes back.
    LaunchedEffect(vm.offline, attempt) { if (!vm.offline || !vm.repairsLoaded) vm.refreshRepairs() }

    val scroll = rememberScrollState()
    val sectionTop = remember { mutableStateMapOf<HubSection, Int>() }
    fun Modifier.section(s: HubSection) = onGloballyPositioned { sectionTop[s] = it.positionInParent().y.toInt() }
    fun book(entry: BookingEntry) = nav.go(vm.begin(entry))

    Column(Modifier.fillMaxSize()) {
        TopBar("Repairs", null, eyebrow = "Repairs hub")
        // Jump links to each section.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HubSection.entries.forEach { s ->
                Box(
                    Modifier.heightIn(min = 48.dp).border(1.dp, SO.line2)
                        .clickable(role = Role.Button, onClickLabel = "Jump to ${s.title}") {
                            val y = sectionTop[s] ?: return@clickable
                            scope.launch { if (reduced) scroll.scrollTo(y) else scroll.animateScrollTo(y) }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(s.short.uppercase(), style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper, maxLines = 1) }
            }
        }
        Rule()
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            HubBlock(HubSection.MINE, Modifier.section(HubSection.MINE)) {
                MyRepairs(vm, nav, tab, { tab = it }, onRetry = { attempt++ }, onRefresh = { scope.launch { vm.refreshRepairs() } })
            }
            HubBlock(HubSection.DEVICES, Modifier.section(HubSection.DEVICES)) {
                val address = vm.defaultAddress
                ServiceStatus(address != null && Catalog.isServiceable(address.pincode), address?.area, address?.pincode)
                DeviceGrid { book(BookingEntry.Device(it)) }
            }
            HubBlock(HubSection.POPULAR, Modifier.section(HubSection.POPULAR)) {
                PopularGrid { p -> book(BookingEntry.Service(p.device, p.issue)) }
                Text("Base prices include GST. Your final quote comes after diagnosis and needs your approval.", style = Type.small, color = SO.mute)
            }
            HubBlock(HubSection.HOW, Modifier.section(HubSection.HOW)) { HowItWorks() }
        }
    }
}

@Composable
private fun HubBlock(section: HubSection, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(section.title, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun MyRepairs(vm: AppViewModel, nav: NavHostController, tab: Int, onTab: (Int) -> Unit, onRetry: () -> Unit, onRefresh: () -> Unit) {
    val lists = listOf(vm.active, vm.completed, vm.cancelled)
    val names = listOf("Active", "Completed", "Cancelled")
    Row(Modifier.fillMaxWidth().border(1.dp, SO.line2)) {
        names.forEachIndexed { i, n ->
            val on = i == tab
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).background(if (on) SO.paper else SO.ink)
                    .selectable(on, role = Role.Tab) { onTab(i) }
                    .semantics(mergeDescendants = true) { contentDescription = "$n" + if (vm.repairsLoaded) ", ${lists[i].size}" else "" },
                contentAlignment = Alignment.Center,
            ) {
                // Name over count, growing with the font scale only up to 1.2x so three tabs always fit.
                val size = (12f * minOf(1f, 1.2f / LocalDensity.current.fontScale)).sp
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(n.uppercase(), style = Type.label.copy(fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 1.sp), color = if (on) SO.ink else SO.mute, maxLines = 1)
                    if (vm.repairsLoaded) Text("${lists[i].size}", style = Type.label.copy(fontSize = size), color = if (on) SO.ink else SO.mute2)
                }
            }
        }
    }
    if (!vm.repairsLoaded) {
        val error = vm.loadError
        if (vm.offline) ErrorPanel("Couldn't load repairs", "You're offline. We'll refresh as soon as you reconnect.", onRetry = onRetry)
        else if (error != null && !vm.isBusy("refresh")) ErrorPanel("Couldn't load repairs", error, onRetry = onRetry)
        else LoadingList()
        return
    }
    if (vm.repairsStale) Row(Modifier.fillMaxWidth().border(1.dp, SO.line2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SHOWING YOUR LAST SAVED COPY", style = Type.label, color = SO.mute, modifier = Modifier.weight(1f))
        TextAction("Refresh", onRefresh)
    }
    val list = lists[tab]
    if (list.isEmpty()) {
        when (tab) {
            0 -> EmptyState("No active repairs", "Nothing in the shop", "Choose a device below, or start a repair, and you'll track every step here.") {
                Btn("Start a repair", { nav.go(vm.begin(BookingEntry.Blank)) }, Modifier.fillMaxWidth())
            }
            1 -> EmptyState("No completed repairs", "None yet", "Finished repairs show up here with the invoice and warranty.")
            else -> EmptyState("No cancellations", "All clear", "Repairs you cancel before pickup appear here.")
        }
    }
    list.forEach { r ->
        when (tab) {
            0 -> ActiveRow(r) { nav.go(Routes.track(r.id)) }
            1 -> CompletedCard(r, nav)
            else -> CancelledCard(r, vm, nav)
        }
    }
}

@Composable
private fun ServiceStatus(serviceable: Boolean, area: String?, pin: String?) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, if (serviceable) SO.line2 else SO.redHot).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(if (serviceable) SO.paper else SO.redHot)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                (if (serviceable) "Serviceable · $area $pin" else if (area != null) "Not in $area yet" else "Add an address to check").uppercase(),
                style = Type.label.copy(fontWeight = FontWeight.Bold), color = if (serviceable) SO.paper else SO.redHot,
            )
            Text(
                if (serviceable) "Pickups today until 7 PM · Bengaluru, Mumbai, Delhi NCR, Pune, Hyderabad"
                else "We'll let you know when doorstep pickup reaches you.",
                style = Type.small, color = SO.mute,
            )
        }
    }
}

/** The six device categories. Two columns on narrow screens or with large text, three otherwise. */
@Composable
private fun DeviceGrid(onPick: (DeviceType) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth < 380.dp || LocalDensity.current.fontScale > 1.2f) 2 else 3
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DeviceType.entries.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { d -> DeviceTile(d, Modifier.weight(1f)) { onPick(d) } }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DeviceTile(d: DeviceType, modifier: Modifier, onClick: () -> Unit) {
    val from = Catalog.fromPrice(d)
    Column(
        modifier.fillMaxHeight().heightIn(min = 104.dp).background(SO.ink2).border(1.dp, SO.line2)
            .clickable(role = Role.Button, onClickLabel = "Book a ${d.label.lowercase()} repair", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${d.label}" + (from?.let { ", repairs from ${rupees(it)}" } ?: "") }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(deviceIcon(d), null, tint = SO.mute, modifier = Modifier.size(24.dp))
        Spacer(Modifier.weight(1f))
        Text(d.label.uppercase(), style = Type.h3.copy(fontSize = 20.sp, lineHeight = 20.sp), color = SO.paper)
        if (from != null) Text("FROM ${rupees(from)}", style = Type.label, color = SO.mute)
    }
}

/**
 * Two columns, or one when the longest title word ("REPLACEMENT", about 5.5em at 20sp) wouldn't fit a half-width
 * tile at the current text size, so words never break mid-way on narrow phones with large text.
 */
@Composable
private fun PopularGrid(onPick: (PopularService) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inner = (maxWidth - 10.dp) / 2 - 28.dp
        val cols = if (inner < 112.dp * LocalDensity.current.fontScale) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            popularServices.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { p -> PopularTile(p, Modifier.weight(1f)) { onPick(p) } }
                }
            }
        }
    }
}

@Composable
private fun PopularTile(p: PopularService, modifier: Modifier, onClick: () -> Unit) {
    val price = Catalog.basePrice(p.device, p.issue) ?: 0
    Column(
        modifier.fillMaxHeight().heightIn(min = 168.dp).background(SO.ink2).border(1.dp, SO.line2)
            .clickable(role = Role.Button, onClickLabel = "Book ${p.title}", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${p.title}, from ${rupees(price)}, ${Catalog.turnaround(p.device, p.issue)}" }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(deviceIcon(p.device), null, tint = SO.mute, modifier = Modifier.size(24.dp))
        Text(p.title.uppercase(), style = Type.h3.copy(fontSize = 20.sp, lineHeight = 20.sp), color = SO.paper)
        Spacer(Modifier.weight(1f))
        Rule(SO.line)
        Price(price, lead = "From", color = SO.paper, leadColor = SO.mute, style = Type.price.copy(fontSize = 30.sp))
        Text(Catalog.turnaround(p.device, p.issue).uppercase(), style = Type.label, color = SO.mute)
    }
}

private val howSteps = listOf(
    "Pickup" to "A verified partner collects your device at a time you choose, sealed in a tamper-evident bag with photos at handover.",
    "Diagnosis" to "A verified repair shop inspects it and finds the fault.",
    "Quote approval" to "You get an itemised quote. Nothing is repaired until you approve it; decline and it comes back free.",
    "Delivery" to "Tested, resealed and returned to your door. Pay after you've checked it works.",
    "Warranty" to "90 days on parts and labour, with free pickup for claims.",
)

@Composable
private fun HowItWorks() {
    Column(Modifier.fillMaxWidth().border(1.dp, SO.line2)) {
        howSteps.forEachIndexed { i, (title, body) ->
            if (i > 0) Rule(SO.line)
            Row(Modifier.fillMaxWidth().padding(14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Text("%02d".format(i + 1), style = Type.monoL, color = SO.redHot, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title.uppercase(), style = Type.h3.copy(fontSize = 20.sp, lineHeight = 21.sp), color = SO.paper)
                    Text(body, style = Type.body, color = SO.mute)
                }
            }
        }
    }
}

@Composable
private fun CardHead(r: Repair, tag: @Composable () -> Unit) {
    // ID and status stack rather than share a row, so a long status never squeezes the ID on narrow phones.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("REPAIR ID ", style = Type.label, color = SO.mute)
            Text(r.id, style = Type.mono.copy(fontWeight = FontWeight.Bold), color = SO.paper, maxLines = 1)
        }
        tag()
    }
}

@Composable
private fun ActiveRow(r: Repair, onClick: () -> Unit) {
    val urgent = r.stage == Stage.QUOTE_PENDING
    Panel(border = if (urgent) SO.red else SO.line2, onClick = onClick) {
        CardHead(r) { StatusTag(r.stage.label, SO.paper, if (urgent) SO.red else SO.ink2) }
        Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.h3, color = SO.paper)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Stage.entries.forEach { s -> Box(Modifier.weight(1f).height(5.dp).background(if (s <= r.stage) SO.red else SO.line)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(etaFor(r).uppercase(), style = Type.label, color = SO.mute, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open tracker", tint = SO.paper, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CompletedCard(r: Repair, nav: NavHostController) {
    val payment = when {
        r.payment != null -> "Paid · ${r.payment.method.label} · ${rupees(r.payment.amount)}"
        r.amountDue == 0 -> "No charge"
        else -> "Due · ${rupees(r.amountDue)}"
    }
    val cover = when {
        r.declined -> "Not applicable · returned unrepaired"
        r.warrantyActive() -> "Until ${r.warrantyUntil!!.format(DMY)} · ${r.warrantyDaysLeft()} days left"
        r.warrantyUntil != null -> "Expired ${r.warrantyUntil!!.format(DMY_SHORT)}"
        else -> "—"
    }
    Panel {
        CardHead(r) {
            when {
                r.declined -> StatusTag("Returned", SO.mute)
                r.needsPayment -> StatusTag("Payment due", SO.paper, SO.red)
                else -> StatusTag("Repair complete", SO.paper)
            }
        }
        Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.h3, color = SO.paper)
        Text("Delivered ${r.deliveredAt?.let { stamp(it) } ?: "—"}", style = Type.small, color = SO.mute)
        Rule(SO.line)
        KeyValue("Invoice", r.invoiceNo ?: "No invoice · nothing charged", mono = r.invoiceNo != null)
        KeyValue("Payment", payment, valueColor = if (r.needsPayment) SO.redHot else SO.paper)
        KeyValue("90-day cover", cover, valueColor = if (r.warrantyActive()) SO.paper else SO.mute)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.needsPayment) Btn("Pay now", { nav.go(Routes.pay(r.id)) }, Modifier.weight(1f), compact = true, icon = null)
            else Btn("Invoice", { nav.go(Routes.invoice(r.id)) }, Modifier.weight(1f), kind = BtnKind.PAPER, compact = true, icon = null)
            Btn("Get help", { nav.go(Routes.report(r.id)) }, Modifier.weight(1f), kind = BtnKind.OUTLINE, compact = true, icon = null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!r.declined) Btn("Warranty", { nav.go(Routes.warranty(r.id)) }, Modifier.weight(1f), kind = BtnKind.OUTLINE, compact = true, icon = null)
            Btn("Status log", { nav.go(Routes.track(r.id)) }, Modifier.weight(1f), kind = BtnKind.OUTLINE, compact = true, icon = null)
        }
    }
}

@Composable
private fun CancelledCard(r: Repair, vm: AppViewModel, nav: NavHostController) {
    Panel {
        CardHead(r) { StatusTag("Cancelled", SO.mute) }
        Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.h3, color = SO.mute)
        KeyValue("Cancelled", r.cancellation?.at?.let { stamp(it) } ?: "—")
        KeyValue("Reason", r.cancellation?.reason ?: "—")
        KeyValue("Charge", "₹0 · cancelled before pickup")
        Btn("Book again", { vm.startBooking(r.device, r.issue); vm.draft = vm.draft.copy(brand = r.brand, model = r.model); nav.go(Routes.BOOK) },
            Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, compact = true)
    }
}
