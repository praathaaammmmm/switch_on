package com.switchon.customer.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.switchon.customer.data.Address
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.data.Validation
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.switchon.customer.data.BusyException
import com.switchon.customer.data.Catalog
import com.switchon.customer.ui.LocalToast
import com.switchon.customer.ui.Routes
import com.switchon.customer.ui.back
import com.switchon.customer.ui.go
import com.switchon.customer.ui.components.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(vm: AppViewModel, nav: NavHostController) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Account", null, eyebrow = "Profile")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Docket(Modifier.fillMaxWidth()) {
                DocketHead("Customer", "Since Jun 2026")
                Text(vm.profile.name.ifBlank { "Guest" }.uppercase(), style = Type.h1, color = SO.ink)
                Row(Modifier.fillMaxWidth()) {
                    DocketField("Mobile", vm.profile.phone.ifBlank { "Added when you book" }, Modifier.weight(1f), valueStyle = Type.mono)
                    DocketField("Repairs", "${vm.repairs.count { !it.isCancelled }}", Modifier.weight(0.5f), valueStyle = Type.h3)
                }
                if (vm.profile.email.isNotBlank()) DocketField("Email", vm.profile.email)
            }
            Column(Modifier.border(1.dp, SO.line2)) {
                LinkRow("Saved addresses", "${vm.addresses.size} saved") { nav.go(Routes.ADDRESSES) }
                Rule(SO.line2)
                LinkRow("Notifications", "Status, quotes, WhatsApp and SMS") { nav.go(Routes.NOTIFICATIONS) }
                Rule(SO.line2)
                LinkRow("Privacy", "Data, permissions and your account") { nav.go(Routes.PRIVACY) }
                Rule(SO.line2)
                LinkRow("90-day warranty", "Cover dates and claims") { nav.go(Routes.warranty()) }
                Rule(SO.line2)
                LinkRow("Help centre", "Plain answers") { nav.go(Routes.HELP) }
            }
            Column(Modifier.border(1.dp, SO.line)) {
                LinkRow("Prototype controls", "Offline, errors, empty account, reset", muted = true) { nav.go(Routes.PROTOTYPE) }
            }
            Btn("Log out", { confirmLogout = true }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null, leadingIcon = Icons.AutoMirrored.Outlined.Logout)
            Text("SWITCH ON · v0.1.0 PROTOTYPE · MADE IN BENGALURU", style = Type.label, color = SO.mute2)
        }
    }
    if (confirmLogout) SoDialog(
        "Log out?", "You'll return to the start screen. Your repairs stay on this device, and you can continue as a guest any time.",
        confirm = "Log out", danger = true, dismiss = "Stay in the app",
        onConfirm = { confirmLogout = false; vm.logout() }, onDismiss = { confirmLogout = false },
    )
}

@Composable
private fun LinkRow(title: String, sub: String, muted: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = Type.h3.copy(fontSize = 20.sp), color = if (muted) SO.mute else SO.paper)
            Text(sub, style = Type.small, color = SO.mute)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = SO.mute, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun AddressesScreen(vm: AppViewModel, nav: NavHostController) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Address?>(null) }
    ScreenScaffold(
        "Saved addresses", { nav.back() }, eyebrow = "Pickup and drop",
        bottom = { Btn("Add new address", { nav.go(Routes.NEW_ADDRESS) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Add) },
    ) {
        if (vm.addresses.isEmpty()) EmptyState("No addresses", "Nowhere saved", "Add your home or office so booking takes seconds.")
        vm.addresses.forEach { a ->
            Panel(border = if (a.isDefault) SO.paper else SO.line2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label.uppercase(), style = Type.h3, color = SO.paper, modifier = Modifier.weight(1f))
                    if (a.isDefault) StatusTag("Default", SO.paper)
                    if (!Catalog.isServiceable(a.pincode)) StatusTag("Not serviceable", SO.redHot)
                }
                Text(a.full, style = Type.body, color = SO.paper)
                if (a.landmark.isNotBlank()) Text(a.landmark, style = Type.small, color = SO.mute)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!a.isDefault) Btn("Make default", {
                        scope.launch { vm.makeDefault(a.id).onSuccess { toast("${a.label} is now your default") }.onFailure { if (it !is BusyException) toast(it.friendly()) } }
                    }, Modifier.weight(1f), kind = BtnKind.OUTLINE, compact = true, icon = null)
                    Btn("Delete", { deleting = a }, Modifier.weight(1f), kind = BtnKind.OUTLINE, compact = true, icon = null)
                }
            }
        }
    }
    deleting?.let { a ->
        val inUse = vm.active.any { it.address.id == a.id }
        SoDialog(
            "Delete ${a.label}?",
            if (inUse) "An active repair uses this address. It will still be delivered there; future bookings won't see it." else "You can add it again any time.",
            confirm = "Delete address", danger = true,
            onConfirm = {
                deleting = null
                scope.launch { vm.deleteAddress(a.id).onSuccess { toast("Address deleted") }.onFailure { if (it !is BusyException) toast(it.friendly()) } }
            }, onDismiss = { deleting = null },
        )
    }
}

@Composable
fun NewAddressScreen(vm: AppViewModel, nav: NavHostController) {
    var label by rememberSaveable { mutableStateOf("Home") }
    var line1 by rememberSaveable { mutableStateOf("") }
    var line2 by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("Bengaluru") }
    var pin by rememberSaveable { mutableStateOf("") }
    var landmark by rememberSaveable { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var locationNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    // Location is asked for only here, while adding a pickup address, and only on tap.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            line2 = "14th Cross, 6th Main"; area = "Malleshwaram"; city = "Bengaluru"; pin = "560003"
            locationNote = "Filled from your location. Add the flat or house number."
        } else {
            locationNote = "Location not shared. Type the address instead. You can change this in Settings."
        }
    }

    // Pincode is checked as soon as six digits are in (or on save); the other fields only on save.
    val pinError = if (tried || pin.length == 6) Validation.pincode(pin) else null
    val valid = line1.isNotBlank() && area.isNotBlank() && city.isNotBlank() && Validation.pincode(pin) == null

    ScreenScaffold(
        "New address", { nav.back() }, eyebrow = "Pickup and drop",
        bottom = {
            Btn(
                "Save address",
                {
                    tried = true
                    if (!valid) return@Btn
                    if (busy) return@Btn
                    busy = true; failure = null
                    scope.launch {
                        vm.saveAddress(Address("", label, line1.trim(), line2.trim(), area.trim(), city.trim(), pin, landmark.trim()))
                            .onSuccess { a ->
                                if (vm.draft.device != null) vm.draft = vm.draft.copy(addressId = a.id)
                                toast("${a.label} saved"); nav.back()
                            }
                            .onFailure { if (it !is BusyException) failure = it.friendly() }
                        busy = false
                    }
                },
                Modifier.fillMaxWidth(), loading = busy,
            )
        },
    ) {
        Btn(
            "Use current location", {
                permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            },
            Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null, leadingIcon = Icons.Outlined.MyLocation,
        )
        locationNote?.let { Text(it, style = Type.small, color = SO.mute) }
        failure?.let { ErrorPanel("Address not saved", it, onRetry = null) }
        Section("Save as") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Home", "Work", "Other").forEach { l -> Tag(l, label == l) { label = l } }
            }
        }
        Field("Flat, house or floor", line1, { line1 = it }, placeholder = "Flat 302, Lake View Residency", error = if (tried && line1.isBlank()) "Needed so the partner finds you." else null, capitalization = KeyboardCapitalization.Words)
        Field("Street and block", line2, { line2 = it }, placeholder = "5th Cross, 2nd Block")
        Field("Area", area, { area = it }, placeholder = "Jayanagar", error = if (tried && area.isBlank()) "Add the area or locality." else null, capitalization = KeyboardCapitalization.Words)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("City", city, { city = it }, Modifier.weight(1f), error = if (tried && city.isBlank()) "Add the city." else null, capitalization = KeyboardCapitalization.Words)
            Field("Pincode", pin, { pin = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), keyboard = KeyboardType.Number, style = Type.monoL, placeholder = "560011")
        }
        if (pinError != null) Text(pinError, style = Type.small, color = SO.redHot, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        else if (pin.length == 6) Row(verticalAlignment = Alignment.CenterVertically) {
            Tick(SO.paper); Spacer(Modifier.width(8.dp))
            Text("SERVICEABLE · DOORSTEP PICKUP AVAILABLE", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.paper)
        }
        Field("Landmark (optional)", landmark, { landmark = it }, placeholder = "Opposite the post office")
    }
}

@Composable
fun NotificationsScreen(vm: AppViewModel, nav: NavHostController) {
    val p = vm.notificationPrefs
    ScreenScaffold("Notifications", { nav.back() }, eyebrow = "Preferences") {
        Section("About your repairs") {
            SwitchToggle(p.statusUpdates, { vm.notificationPrefs = p.copy(statusUpdates = it) }, "Status updates", "Pickup, arrival at shop, out for delivery")
            Rule(SO.line)
            SwitchToggle(p.quoteAlerts, { vm.notificationPrefs = p.copy(quoteAlerts = it) }, "Quote alerts", "The moment a final quote needs your approval")
        }
        if (!p.quoteAlerts) Panel(border = SO.redHot) {
            Text("HEADS UP", style = Type.label.copy(fontWeight = FontWeight.Bold), color = SO.redHot)
            Text("Repairs can't start until you approve the quote. Without alerts, check the app so your device isn't held up.", style = Type.body, color = SO.paper)
        }
        Section("Channels") {
            SwitchToggle(p.whatsapp, { vm.notificationPrefs = p.copy(whatsapp = it) }, "WhatsApp", "Updates on ${vm.profile.phone}")
            Rule(SO.line)
            SwitchToggle(p.sms, { vm.notificationPrefs = p.copy(sms = it) }, "SMS", "OTPs are always sent by SMS")
        }
        Section("Other") {
            SwitchToggle(p.offers, { vm.notificationPrefs = p.copy(offers = it) }, "Offers and tips", "At most twice a month")
        }
    }
}

@Composable
fun PrivacyScreen(vm: AppViewModel, nav: NavHostController) {
    val p = vm.privacyPrefs
    val toast = LocalToast.current
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold("Privacy", { nav.back() }, eyebrow = "Your data") {
        Text("Technicians open only what they need to test the repair. Handover photos show the device, never you or your home.", style = Type.bodyL, color = SO.paper)
        Section("Sharing") {
            SwitchToggle(p.showPhoneToPartner, { vm.privacyPrefs = p.copy(showPhoneToPartner = it) }, "Show my number to partners", "Off: calls go through a masked Switch On number")
            Rule(SO.line)
            SwitchToggle(p.shareDiagnostics, { vm.privacyPrefs = p.copy(shareDiagnostics = it) }, "Share repair diagnostics", "Anonymised fault data that improves our estimates")
            Rule(SO.line)
            SwitchToggle(p.personalisedOffers, { vm.privacyPrefs = p.copy(personalisedOffers = it) }, "Personalised offers", "Based on your devices and past repairs")
        }
        Section("Permissions") {
            KeyValue("Location", "Asked only when adding a pickup address")
            KeyValue("Photos", "Only the ones you pick for a booking")
            KeyValue("Contacts", "Never")
        }
        Section("Your account") {
            Btn("Download my data", { toast("We'll email a copy to ${vm.profile.email} within 24 hours") }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
            Btn("Delete account", { confirmDelete = true }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
        }
    }
    if (confirmDelete) {
        val blocked = vm.active.isNotEmpty()
        SoDialog(
            if (blocked) "Finish your repair first" else "Delete account?",
            if (blocked) "You have an active repair. Once it's delivered and paid, you can delete your account here."
            else "We'll erase your profile and addresses within 30 days. Invoices are kept for 8 years as required by GST law.",
            confirm = if (blocked) "OK" else "Delete account", danger = !blocked, dismiss = "Cancel",
            onConfirm = { confirmDelete = false; if (!blocked) { toast("Deletion requested"); vm.logout() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Switches for demonstrating offline, error, empty and payment states. Not part of the shipped app. */
@Composable
fun PrototypeScreen(vm: AppViewModel, nav: NavHostController) {
    val toast = LocalToast.current
    ScreenScaffold("Prototype controls", { nav.back() }, eyebrow = "Demo only") {
        Text("These switches simulate states that are hard to reach on a real device. They're not part of the customer app.", style = Type.body, color = SO.mute)
        Section("Network") {
            SwitchToggle(vm.simulateOffline, { vm.simulateOffline = it }, "Simulate offline", if (vm.deviceOnline) "Device is online" else "Device is actually offline")
            Rule(SO.line)
            SwitchToggle(vm.failNextRequest, { vm.failNextRequest = it }, "Fail the next request", "Shows the server-error state once, then clears")
        }
        Section("Data") {
            // Demo data lives in the app; in the prod build it lives on the server, so these would mislead.
            if (vm.isDemo) {
                Btn("Reset demo data", { vm.resetDemo(empty = false); toast("Demo data restored") }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
                Btn("Start with an empty account", { vm.resetDemo(empty = true); toast("All repairs cleared") }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
            } else {
                Text("Repairs are stored on the Switch On server for this guest. Use the backend's dev tools to seed or advance them.", style = Type.small, color = SO.mute)
            }
            Btn("Reload repairs list", { vm.repairsLoaded = false; toast("Repairs will reload when opened") }, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, icon = null)
        }
        Section("Walkthrough") {
            Text("1. Home → Review quote on SO-482917 to approve or decline.\n2. In the tracker, tap Next update to move an order on.\n3. After delivery, pay by UPI, card or cash.\n4. Repairs → Completed → Warranty to raise a claim on the Dell laptop.", style = Type.body, color = SO.paper)
        }
    }
}
