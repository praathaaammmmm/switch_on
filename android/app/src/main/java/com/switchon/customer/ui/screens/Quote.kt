package com.switchon.customer.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.NavHostController
import com.switchon.customer.data.BusyException
import com.switchon.customer.data.Validation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.DMY
import com.switchon.customer.data.Decision
import com.switchon.customer.data.ItemKind
import com.switchon.customer.data.PayMethod
import com.switchon.customer.data.Repair
import com.switchon.customer.data.Stage
import com.switchon.customer.data.estimateVerdict
import com.switchon.customer.data.rupeeRange
import com.switchon.customer.data.rupees
import com.switchon.customer.data.stamp
import com.switchon.customer.ui.LocalToast
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
fun QuoteScreen(vm: AppViewModel, nav: NavHostController, id: String?) {
    val r = vm.repair(id) ?: return MissingRepair(nav)
    val q = r.quote
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var confirmApprove by remember { mutableStateOf(false) }
    var confirmDecline by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    if (q == null) {
        ScreenScaffold("Final quote", { nav.back() }, eyebrow = r.id) {
            EmptyState("Quote · pending", "Not ready yet", "The shop sends an itemised quote after diagnosis, usually within 4 hours of the device arriving.")
        }
        return
    }
    val pending = q.decision == null && r.stage == Stage.QUOTE_PENDING && r.isActive

    ScreenScaffold(
        "Final quote", { nav.back() }, eyebrow = r.id,
        bottom = if (pending) ({
            Btn("Approve repair · ${rupees(q.total)}", { confirmApprove = true }, Modifier.fillMaxWidth(), enabled = !vm.offline)
            Btn("Return my device", { confirmDecline = true }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = Icons.Outlined.Undo, enabled = !vm.offline)
        }) else ({
            Btn("Back to tracker", { nav.backToTracker(r.id, from = "quote/{id}") }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER)
        }),
    ) {
        when (q.decision) {
            Decision.APPROVED -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp("Quote approved", big = true, color = SO.redHot) }
            Decision.DECLINED -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp("Returning unrepaired", big = true, color = SO.redHot) }
            null -> {
                Text("Nothing has been repaired yet. Work starts only after you approve this quote.", style = Type.bodyL, color = SO.paper)
            }
        }
        failure?.let { ErrorPanel("Decision not sent", it, onRetry = null) }
        if (vm.offline && pending) ErrorPanel("You're offline", "Reconnect to approve or decline. The shop will hold your device until you decide.", onRetry = null)

        QuoteDocket(r)
        EstimateCompare(r)

        Panel(border = SO.paper) {
            Text("YOUR CHOICE", style = Type.label, color = SO.redHot)
            Text("Approve and the repair starts within the hour. Decline and the device is resealed and returned to you free. Nothing to pay either way until delivery.", style = Type.body, color = SO.paper)
        }
        if (q.decision == Decision.DECLINED) {
            KeyValue("Declined", "${q.decidedAt?.let { stamp(it) }} · ${q.declineReason}")
        }
    }

    if (confirmApprove) {
        SoDialog(
            "Approve ${rupees(q.total)}?",
            "${r.shop?.name ?: "The shop"} will start the repair now. You pay after delivery by UPI, card or cash.",
            confirm = "Approve repair", loading = busy,
            onConfirm = {
                if (!busy) {
                    busy = true; failure = null
                    scope.launch {
                        vm.approveQuote(r.id).onSuccess { toast("Approved. Repair starting.") }
                            .onFailure { if (it !is BusyException) failure = it.friendly() }
                        busy = false; confirmApprove = false
                    }
                }
            },
            onDismiss = { confirmApprove = false },
        )
    }
    if (confirmDecline) {
        val reasons = listOf("Too expensive", "Costs more than a new device", "I'll get it fixed elsewhere", "Changed my mind")
        var reason by remember { mutableStateOf(reasons[0]) }
        SoDialog(
            "Return without repair?",
            "We reseal it exactly as it is and bring it back. No diagnosis fee, nothing to pay.",
            confirm = "Return my device", danger = true, dismiss = "Go back", loading = busy,
            onConfirm = {
                if (!busy) {
                    busy = true; failure = null
                    scope.launch {
                        vm.declineQuote(r.id, reason).onSuccess { toast("Quote declined. Device coming back.") }
                            .onFailure { if (it !is BusyException) failure = it.friendly() }
                        busy = false; confirmDecline = false
                    }
                }
            },
            onDismiss = { confirmDecline = false },
            extra = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { reasons.forEach { Chip(it, reason == it, { reason = it }, Modifier.fillMaxWidth()) } } },
        )
    }
}

/** Itemised final quote on torn paper. */
@Composable
fun QuoteDocket(r: Repair) {
    val q = r.quote ?: return
    val reduced = LocalReducedMotion.current
    Docket(Modifier.fillMaxWidth().scanLine(reduced), shadow = if (q.decision == null) SO.red else null) {
        DocketHead("Final quote", r.id)
        Text("${r.title} · ${r.jobTitle}".uppercase(), style = Type.h3, color = SO.ink)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DIAGNOSED PROBLEM", style = Type.label, color = SO.pencil)
            Text(q.diagnosis, style = Type.bodyStrong, color = SO.ink)
            q.findings.forEach { f ->
                Row { Text("—", style = Type.small, color = SO.pencil, modifier = Modifier.width(18.dp)); Text(f, style = Type.small, color = SO.pencil) }
            }
            Text("${r.shop?.name ?: "Repair shop"} · ${stamp(q.sentAt)}".uppercase(), style = Type.label.copy(fontSize = 10.sp), color = SO.pencil)
        }
        Rule(SO.ink, 2.dp)
        listOf(ItemKind.PART to "Parts", ItemKind.LABOUR to "Labour", ItemKind.FEE to "Fees").forEach { (kind, title) ->
            val items = q.items.filter { it.kind == kind }
            if (items.isNotEmpty()) {
                Text(title.uppercase(), style = Type.label, color = SO.pencil)
                items.forEach { ReceiptLine(it.label, it.detail.takeIf { d -> d != "Part" }, if (it.amount == 0) "FREE" else rupees(it.amount)) }
            }
        }
        DashedRule()
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("TOTAL · GST INCLUDED", style = Type.label, color = SO.pencil)
                Text(rupees(q.total), style = Type.hero.copy(fontSize = 60.sp, lineHeight = 54.sp), color = SO.ink)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            DocketField("Warranty", q.warranty, Modifier.weight(1f))
            DocketField("Pay", "After delivery", Modifier.weight(1f))
        }
    }
}

/** Where the final price lands against the original estimate range. */
@Composable
private fun EstimateCompare(r: Repair) {
    val q = r.quote ?: return
    if (r.warrantyClaimFor != null) return
    val verdict = estimateVerdict(r, q.total)
    val over = q.total > r.estimateHigh
    Column(
        Modifier.fillMaxWidth().border(1.dp, if (over) SO.redHot else SO.line2).padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Final ${rupees(q.total)}, estimate ${rupeeRange(r.estimateLow, r.estimateHigh)}, $verdict" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("VS. YOUR ESTIMATE", style = Type.label, color = SO.mute)
        Row {
            Column(Modifier.weight(1f)) {
                Text("ESTIMATE", style = Type.label, color = SO.mute)
                Text(rupeeRange(r.estimateLow, r.estimateHigh), style = Type.mono, color = SO.paper)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("FINAL", style = Type.label, color = SO.mute)
                Text(rupees(q.total), style = Type.price.copy(fontSize = 28.sp), color = SO.paper)
            }
        }
        // Range bar: the estimate band in paper, the final price as a red tick.
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
            val lo = (r.estimateLow * 0.8f)
            val hi = maxOf(r.estimateHigh * 1.2f, q.total * 1.05f)
            fun x(v: Float) = maxWidth * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(2.dp).offset(y = 10.dp).background(SO.line2))
            Box(Modifier.offset(x = x(r.estimateLow.toFloat()), y = 6.dp).width(x(r.estimateHigh.toFloat()) - x(r.estimateLow.toFloat())).height(10.dp).background(SO.paper))
            Box(Modifier.offset(x = x(q.total.toFloat()) - 2.dp).width(4.dp).fillMaxHeight().background(SO.redHot))
        }
        Text(verdict.replaceFirstChar { it.uppercase() } + ".", style = Type.bodyStrong, color = if (over) SO.redHot else SO.paper)
        if (over) Text("The shop found more damage than the base price covers. The breakdown above shows why. You can still return it free.", style = Type.small, color = SO.mute)
    }
}

// ---- Payment --------------------------------------------------------------------------------------------

@Composable
fun PayScreen(vm: AppViewModel, nav: NavHostController, id: String?) {
    val r = vm.repair(id) ?: return MissingRepair(nav)
    var method by rememberSaveable { mutableStateOf(PayMethod.UPI) }
    var upiId by rememberSaveable { mutableStateOf("") }
    var cashHanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val upiError = if (method == PayMethod.UPI && upiId.isNotEmpty()) Validation.upiId(upiId) else null
    val ready = when (method) {
        PayMethod.UPI -> Validation.upiId(upiId) == null
        PayMethod.CASH -> cashHanded
        PayMethod.CARD -> true
    }

    fun pay() {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            vm.pay(r.id, method, upiId, cashHanded).onSuccess { toast("Paid. Invoice ready.") }
                .onFailure { if (it !is BusyException) failure = it.friendly() }
            busy = false
        }
    }

    ScreenScaffold(
        "Pay for repair", { if (!busy) nav.back() }, eyebrow = r.id,
        bottom = {
            when {
                r.payment != null -> Btn("View invoice", { nav.go(Routes.invoice(r.id)) { popUpTo("pay/{id}") { inclusive = true } } }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER)
                !r.needsPayment -> Btn("Back to tracker", { nav.backToTracker(r.id, from = "pay/{id}") }, Modifier.fillMaxWidth(), kind = BtnKind.PAPER)
                else -> {
                    Btn(
                        when (method) { PayMethod.CASH -> "Confirm cash paid"; PayMethod.CARD -> "Pay ${rupees(r.amountDue)} by card"; PayMethod.UPI -> "Pay ${rupees(r.amountDue)}" },
                        ::pay, Modifier.fillMaxWidth(), loading = busy, enabled = !vm.offline && ready,
                    )
                    if (vm.offline) Text("You're offline. Nothing will be charged until you reconnect.", style = Type.small, color = SO.redHot)
                }
            }
        },
    ) {
        when {
            r.payment != null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp("Paid · ${r.payment.method.label}", big = true, color = SO.redHot) }
            !r.needsPayment -> EmptyState("Nothing due", "Nothing to pay", if (r.isDelivered) "This repair costs you nothing." else "You pay only after the device is delivered and you've checked it works.")
            else -> Text("Your device is back. Check it works, then pay. Nothing is charged until you confirm.", style = Type.bodyL, color = SO.paper)
        }
        failure?.let { ErrorPanel("Payment didn't go through", "$it No money was taken.", onRetry = if (ready && !vm.offline) ::pay else null) }
        Docket(Modifier.fillMaxWidth()) {
            DocketHead(if (r.payment != null) "Amount paid" else "Amount due", r.id)
            Text(rupees(if (r.payment != null) r.payment.amount else r.amountDue), style = Type.hero.copy(fontSize = 64.sp, lineHeight = 58.sp), color = SO.ink)
            Text("${r.title} · ${r.jobTitle} · GST included", style = Type.small, color = SO.pencil)
        }
        if (r.needsPayment) Section("Pay with") {
            PayOption(PayMethod.UPI, method, Icons.Outlined.AccountBalance, "UPI", "Google Pay, PhonePe, Paytm, BHIM or any UPI app") { method = it }
            if (method == PayMethod.UPI) Field(
                "UPI ID", upiId, { upiId = it.trim() }, placeholder = "priya@okhdfcbank", error = upiError,
                keyboard = KeyboardType.Email, style = Type.mono.copy(fontSize = 17.sp), imeDone = { if (ready) pay() },
            )
            PayOption(PayMethod.CARD, method, Icons.Outlined.CreditCard, "Card", "Debit or credit · Visa, Mastercard, RuPay") { method = it }
            if (method == PayMethod.CARD) Text("Card details are entered on the bank's secure page. Switch On never sees your card number.", style = Type.small, color = SO.mute)
            PayOption(PayMethod.CASH, method, Icons.Outlined.Payments, "Cash", "Hand it to ${r.courier?.name ?: "your partner"}. They'll confirm on their app.") { method = it }
            if (method == PayMethod.CASH) SwitchToggle(cashHanded, { cashHanded = it }, "I've handed over ${rupees(r.amountDue)}", "The partner confirms the same amount on their side")
        }
    }
}

@Composable
private fun PayOption(m: PayMethod, selected: PayMethod, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onPick: (PayMethod) -> Unit) {
    Chip(title, m == selected, { onPick(m) }, Modifier.fillMaxWidth(), icon = icon, sub = sub)
}

// ---- Invoice --------------------------------------------------------------------------------------------

@Composable
fun InvoiceScreen(vm: AppViewModel, nav: NavHostController, id: String?) {
    val r = vm.repair(id) ?: return MissingRepair(nav)
    val q = r.quote
    val ctx = LocalContext.current
    val total = r.amountDue
    val taxable = Math.round(total / 1.18).toInt()
    val gst = total - taxable

    fun share() {
        val text = buildString {
            appendLine("Switch On · Tax invoice ${r.invoiceNo ?: ""}")
            appendLine("${r.id} · ${r.title} · ${r.jobTitle}")
            q?.items?.forEach { appendLine("${it.label}: ${if (it.amount == 0) "Free" else rupees(it.amount)}") }
            appendLine("Total: ${rupees(total)} (GST included)")
            r.payment?.let { appendLine("Paid by ${it.method.label} · ${it.ref}") }
            r.warrantyUntil?.let { appendLine("Warranty until ${it.format(DMY)}") }
        }
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invoice")) }
    }

    ScreenScaffold(
        "Invoice", { nav.back() }, eyebrow = r.id,
        actions = { SquareIconBtn(Icons.Outlined.Share, "Share invoice", ::share) },
        bottom = {
            if (r.needsPayment) Btn("Pay ${rupees(r.amountDue)}", { nav.go(Routes.pay(r.id)) }, Modifier.fillMaxWidth())
            Btn("Get help with this repair", { nav.go(Routes.report(r.id)) }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE)
        },
    ) {
        Docket(Modifier.fillMaxWidth(), tornTop = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SWITCH ON", style = Type.h2, color = SO.ink)
                    Text("Switch On Repair Services Pvt. Ltd.\nGSTIN 29AAKCS4821M1Z6", style = Type.label.copy(fontSize = 10.sp, letterSpacing = 0.6.sp), color = SO.pencil)
                }
                when {
                    r.payment != null -> Stamp("Paid", big = true)
                    total == 0 -> Stamp("No charge", big = true)
                    else -> Stamp("Due", big = true)
                }
            }
            DocketHead("Tax invoice", r.invoiceNo ?: "—")
            Row(Modifier.fillMaxWidth()) {
                DocketField("Repair ID", r.id, Modifier.weight(1f), valueStyle = Type.mono)
                DocketField("Delivered", r.deliveredAt?.toLocalDate()?.format(DMY) ?: "—", Modifier.weight(1f))
            }
            DocketField("Billed to", "${r.contactName.ifBlank { vm.profile.name }}\n${r.address.full}")
            DocketField("Device", "${r.title} · ${r.jobTitle}")
            Rule(SO.ink, 2.dp)
            q?.items?.forEach { ReceiptLine(it.label, it.detail.takeIf { d -> d != "Part" }, if (it.amount == 0) "FREE" else rupees(it.amount)) }
            DashedRule()
            ReceiptLine("Taxable value", null, rupees(taxable))
            ReceiptLine("CGST 9%", null, rupees(gst / 2))
            ReceiptLine("SGST 9%", null, rupees(gst - gst / 2))
            Rule(SO.ink, 2.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("TOTAL", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.ink, modifier = Modifier.weight(1f).padding(bottom = 6.dp))
                Text(rupees(total), style = Type.hero.copy(fontSize = 52.sp, lineHeight = 48.sp), color = SO.ink)
            }
            Row(Modifier.fillMaxWidth()) {
                DocketField("Payment", r.payment?.let { "${it.method.label} · ${stamp(it.at)}" } ?: if (total == 0) "Nothing to pay" else "Pending", Modifier.weight(1f))
                DocketField("90-day cover", r.warrantyUntil?.let { "Until ${it.format(DMY)}" } ?: "Not applicable", Modifier.weight(1f))
            }
            r.payment?.let { Text(it.ref.uppercase(), style = Type.label.copy(fontSize = 10.sp), color = SO.pencil) }
        }
    }
}

/**
 * Return to this repair's tracker. If it's the screen underneath, just pop; otherwise open it in place of
 * the current screen ([from] is that screen's route pattern) so Back doesn't land on a stale copy.
 */
fun NavHostController.backToTracker(id: String, from: String) {
    val prev = previousBackStackEntry
    if (prev?.destination?.route == "track/{id}" && prev.arguments?.getString("id") == id) back()
    else go(Routes.track(id)) { popUpTo(from) { inclusive = true } }
}
