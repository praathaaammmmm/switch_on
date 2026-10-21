package com.switchon.customer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.ReportProblem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.BusyException
import com.switchon.customer.data.QueuedException
import com.switchon.customer.data.stamp
import com.switchon.customer.data.time
import com.switchon.customer.ui.LocalToast
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.launch

const val SUPPORT_PHONE = "1800 572 4826"

fun dialSupport(ctx: android.content.Context) =
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + SUPPORT_PHONE.replace(" ", "")))) }

@Composable
fun SupportScreen(vm: AppViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar("Support", null, eyebrow = "8 AM – 10 PM, every day")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Display("How can we help?", style = Type.h1)
            vm.active.firstOrNull()?.let { r ->
                Panel(border = SO.paper, onClick = { nav.go(Routes.report(r.id)) }) {
                    Text("ABOUT YOUR CURRENT REPAIR", style = Type.label, color = SO.mute)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.id, style = Type.mono, color = SO.paper)
                            Text("${r.title} · ${r.stage.label}".uppercase(), style = Type.h3, color = SO.paper)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = SO.paper)
                    }
                }
            }
            Column(Modifier.border(1.dp, SO.line2)) {
                SupportRow(Icons.AutoMirrored.Outlined.Chat, "Chat with us", "Average reply under 2 minutes") { nav.go(Routes.CHAT) }
                Rule(SO.line2)
                SupportRow(Icons.Outlined.Call, "Call $SUPPORT_PHONE", "Toll-free · English and Hindi") { dialSupport(ctx) }
                Rule(SO.line2)
                SupportRow(Icons.Outlined.ReportProblem, "Report an issue", "Damage, delays, billing or a broken seal") { nav.go(Routes.report()) }
                Rule(SO.line2)
                SupportRow(Icons.Outlined.MenuBook, "Help centre", "Pricing, pickups, payments and warranty") { nav.go(Routes.HELP) }
            }
            if (vm.tickets.isNotEmpty()) Section("Your tickets") {
                vm.tickets.forEach { t ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.id, style = Type.mono.copy(fontWeight = FontWeight.Bold), color = SO.paper, modifier = Modifier.weight(1f))
                            StatusTag("Open", SO.paper)
                        }
                        Text(t.topic, style = Type.bodyStrong, color = SO.paper)
                        Text("${t.repairId ?: "General"} · ${stamp(t.at)} · reply within 4 hours", style = Type.small, color = SO.mute)
                    }
                }
            }
            Section("Quick answers") { Faqs(faqs.take(3)) }
            TextAction("See all help articles", { nav.go(Routes.HELP) })
        }
    }
}

@Composable
private fun SupportRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = SO.redHot, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = Type.h3.copy(fontSize = 20.sp), color = SO.paper)
            Text(sub, style = Type.small, color = SO.mute)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = SO.mute, modifier = Modifier.size(20.dp))
    }
}

private val faqs = listOf(
    "Is the online price final?" to "No. It's a published base estimate. The shop diagnoses the device and sends an itemised final quote. Nothing is repaired until you approve it in the app.",
    "When do I pay?" to "Only after the device is back with you and you've checked it. Pay by UPI, card or cash to the delivery partner.",
    "What if I decline the quote?" to "We reseal the device as it is and return it free. There's no diagnosis fee and nothing to pay.",
    "How do I know my device is safe?" to "It's sealed in a tamper-evident bag in front of you, with photos at every handover. Check the seal code in the tracker matches the bag.",
    "Can I cancel?" to "Yes, free, any time before the pickup partner sets off. Open the tracker and tap Cancel.",
    "What does the 90-day warranty cover?" to "The same fault returning and any faulty replacement part, with free pickup. New drops, liquid damage or other shops' work aren't covered.",
    "Should I back up my data?" to "Please do if you can. Technicians open only what they need to test the repair, and you can remove your screen lock code after delivery.",
)

@Composable
private fun Faqs(list: List<Pair<String, String>>) {
    Column(Modifier.border(1.dp, SO.line2)) {
        list.forEachIndexed { i, (q, a) ->
            if (i > 0) Rule(SO.line2)
            var open by rememberSaveable(q) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                    .padding(14.dp).animateContentSize(),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(q, style = Type.bodyStrong, color = SO.paper, modifier = Modifier.weight(1f))
                    Text(if (open) "–" else "+", style = Type.h3, color = SO.redHot)
                }
                if (open) Text(a, style = Type.body, color = SO.mute, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun HelpScreen(nav: NavHostController) {
    ScreenScaffold("Help centre", { nav.back() }, eyebrow = "Plain answers") {
        Section("Pricing and quotes") { Faqs(faqs.subList(0, 3)) }
        Section("Pickup and custody") { Faqs(faqs.subList(3, 5)) }
        Section("Warranty and data") { Faqs(faqs.subList(5, 7)) }
        Btn("Still stuck? Chat with us", { nav.go(Routes.CHAT) }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER)
    }
}

@Composable
fun ChatScreen(vm: AppViewModel, nav: NavHostController) {
    var text by rememberSaveable { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val ctx = LocalContext.current
    LaunchedEffect(vm.chat.size, waiting) { if (vm.chat.isNotEmpty()) list.animateScrollToItem(vm.chat.size - 1 + if (waiting) 1 else 0) }

    fun send(msg: String = text) {
        if (msg.isBlank() || waiting) return
        text = ""; waiting = true; failed = null
        scope.launch {
            vm.sendChat(msg).onFailure { failed = it.friendly() }
            waiting = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Neha · Support", { nav.back() }, eyebrow = "Online now") {
            SquareIconBtn(Icons.Outlined.Call, "Call support", { dialSupport(ctx) })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.chat) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromCustomer) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 300.dp).background(if (m.fromCustomer) SO.paper else SO.ink2)
                            .border(1.dp, if (m.fromCustomer) SO.paper else SO.line2).padding(12.dp),
                    ) {
                        Text(m.text, style = Type.body, color = if (m.fromCustomer) SO.ink else SO.paper)
                        Text((if (m.fromCustomer) "You · " else "Neha · ") + time(m.at), style = Type.label.copy(fontSize = 10.sp), color = if (m.fromCustomer) SO.pencil else SO.mute)
                    }
                }
            }
            if (waiting) item { Text("NEHA IS TYPING…", style = Type.label, color = SO.mute) }
            failed?.let { item { ErrorPanel("Message not sent", it, onRetry = null) } }
        }
        Rule()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Where is my device?", "Explain my quote", "How do I pay?").forEach { s ->
                Box(Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, SO.line2).clickable(role = Role.Button) { send(s) }.padding(6.dp), contentAlignment = Alignment.Center) {
                    Text(s, style = Type.small, color = SO.paper, maxLines = 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(SO.ink).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Field("Message", text, { text = it }, Modifier.weight(1f), placeholder = "Type your message", imeDone = { send() })
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(56.dp).background(if (text.isBlank()) SO.ink3 else SO.red).clickable(enabled = text.isNotBlank(), role = Role.Button, onClickLabel = "Send") { send() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (text.isBlank()) SO.mute2 else SO.paper) }
        }
    }
}

@Composable
fun ReportScreen(vm: AppViewModel, nav: NavHostController, repairId: String?) {
    val topics = listOf("Device damaged", "Seal broken or code mismatch", "Pickup or delivery late", "Billing or payment", "Something else")
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf("") }
    var repair by rememberSaveable { mutableStateOf(repairId) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val tooShort = detail.trim().length < 10
    ScreenScaffold(
        "Report an issue", { nav.back() }, eyebrow = repair ?: "Support",
        bottom = {
            if (done == null) Btn(
                "Send report",
                {
                    if (busy) return@Btn
                    busy = true; failure = null
                    scope.launch {
                        vm.reportIssue(repair, topic!!, detail).onSuccess { done = it; toast("Ticket $it created") }.onFailure {
                            when (it) {
                                is BusyException -> Unit
                                is QueuedException -> { toast(it.friendly()); nav.back() }
                                else -> failure = it.friendly()
                            }
                        }
                        busy = false
                    }
                },
                Modifier.fillMaxWidth(), enabled = topic != null && !tooShort && (!vm.offline || vm.canQueue), loading = busy,
            ) else Btn("Done", { nav.back() }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER, icon = null)
        },
    ) {
        if (done != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp("Ticket open", big = true, color = SO.redHot) }
            Docket(Modifier.fillMaxWidth()) {
                DocketHead("Ticket", done)
                DocketField("Topic", topic ?: "")
                DocketField("Repair", repair ?: "General")
                DocketField("Next", "A support lead replies within 4 hours, by 10 PM today at the latest. Urgent seal or damage issues are called back in 30 minutes.")
            }
            return@ScreenScaffold
        }
        failure?.let { ErrorPanel("Report not sent", it, onRetry = null) }
        Section("Which repair?") {
            val options = (vm.active + vm.completed).take(4)
            options.forEach { r -> Chip("${r.id} · ${r.title}", repair == r.id, { repair = r.id }, Modifier.fillMaxWidth()) }
            Chip("Not about a repair", repair == null, { repair = null }, Modifier.fillMaxWidth())
        }
        Section("What happened?") { topics.forEach { t -> Chip(t, topic == t, { topic = t }, Modifier.fillMaxWidth()) } }
        Field(
            "Details", detail, { detail = it.take(500) }, placeholder = "What did you notice, and when?",
            singleLine = false, minLines = 4, style = Type.body,
            error = if (detail.isNotEmpty() && tooShort) "A few more words help us act faster." else null,
        )
        if (topic == "Seal broken or code mismatch") {
            Panel(border = SO.redHot) {
                Text("DON'T ACCEPT THE DEVICE", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.redHot)
                Text("Hand it back to the partner unopened. We'll call you within 30 minutes and investigate the custody log.", style = Type.body, color = SO.paper)
            }
        }
    }
}
