package com.switchon.customer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavOptionsBuilder
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.switchon.customer.SwitchOnApp
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.ui.components.BottomNav
import com.switchon.customer.ui.components.LocalReducedMotion
import com.switchon.customer.ui.components.NavItem
import com.switchon.customer.ui.components.OfflineBanner
import com.switchon.customer.ui.components.Tick
import com.switchon.customer.ui.components.rememberSystemReducedMotion
import com.switchon.customer.ui.screens.*
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.launch

/** Shows a short message in the branded snackbar. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

object Routes {
    const val HOME = "home"
    const val REPAIRS = "repairs"
    const val SUPPORT = "support"
    const val ACCOUNT = "account"
    const val BOOK = "book"
    fun booked(id: String) = "booked/$id"
    fun track(id: String) = "track/$id"
    fun quote(id: String) = "quote/$id"
    fun invoice(id: String) = "invoice/$id"
    fun pay(id: String) = "pay/$id"
    fun warranty(id: String? = null) = if (id == null) "warranty" else "warranty?id=$id"
    fun claim(id: String) = "claim/$id"
    fun report(id: String? = null) = if (id == null) "report" else "report?repair=$id"
    const val CHAT = "chat"
    const val HELP = "help"
    const val ADDRESSES = "addresses"
    const val NEW_ADDRESS = "address/new"
    const val NOTIFICATIONS = "notifications"
    const val PRIVACY = "privacy"
    const val PROTOTYPE = "prototype"
    /**
     * Preliminary device check; `from` is home, booking, review (the report attached to the draft) or saved (the
     * report kept with a booked repair, named by `repair`). The last two are read-only.
     */
    const val DIAGNOSTICS = "diagnostics"
    const val DIAGNOSTICS_PATTERN = "diagnostics?from={from}&repair={repair}"
    fun diagnostics(origin: CheckOrigin) = "$DIAGNOSTICS?from=${origin.name}"
    fun savedCheck(repairId: String) = "$DIAGNOSTICS?from=${CheckOrigin.SAVED.name}&repair=$repairId"
}

private val tabs = listOf(Routes.HOME, Routes.REPAIRS, Routes.SUPPORT, Routes.ACCOUNT)

@Composable
fun App() {
    val graph = (LocalContext.current.applicationContext as SwitchOnApp).graph
    val vm: AppViewModel = viewModel { AppViewModel(graph.backend, graph.network, graph.session, graph.queue, reports = graph.reports) }
    // Load (cached first, then fresh) whenever a guest is in the app.
    LaunchedEffect(vm.signedIn) { if (vm.signedIn) vm.open() }
    // When queued bookings or tickets are sent in the background, show the result.
    LaunchedEffect(graph.queue) {
        var last = -1
        graph.queue?.changes?.collect { count ->
            vm.refreshPending()
            if (last >= 0 && count < last && vm.signedIn) vm.refreshRepairs()
            last = count
        }
    }
    val reduced = rememberSystemReducedMotion()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) } }

    CompositionLocalProvider(LocalReducedMotion provides reduced, LocalToast provides toast) {
        Box(Modifier.fillMaxSize().background(SO.ink)) {
            AnimatedContent(
                targetState = vm.signedIn,
                transitionSpec = { fadeIn(tween(if (reduced) 0 else 260)) togetherWith fadeOut(tween(if (reduced) 0 else 200)) },
                label = "auth",
            ) { signedIn ->
                if (signedIn) MainShell(vm) else AuthFlow(vm)
            }
            // Top placement keeps messages clear of the sticky CTAs at the bottom of most screens.
            SnackbarHost(snack, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 72.dp, start = 16.dp, end = 16.dp)) { data ->
                Row(
                    Modifier.fillMaxWidth().background(SO.paper).border(2.dp, SO.ink).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tick(SO.red, 10.dp); Spacer(Modifier.width(12.dp))
                    Text(data.visuals.message, style = Type.bodyStrong, color = SO.ink)
                }
            }
        }
    }
}

@Composable
private fun MainShell(vm: AppViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val reduced = LocalReducedMotion.current
    val dur = if (reduced) 0 else 240

    Column(Modifier.fillMaxSize().background(SO.ink)) {
        Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars))
        if (vm.offline) OfflineBanner(onRetry = if (vm.simulateOffline) ({ vm.simulateOffline = false }) else null)
        Box(Modifier.weight(1f)) {
            NavHost(
                nav, startDestination = Routes.HOME,
                enterTransition = { if (targetState.destination.route in tabs) fadeIn(tween(dur)) else slideInHorizontally(tween(dur)) { it / 5 } + fadeIn(tween(dur)) },
                exitTransition = { fadeOut(tween(dur / 2)) },
                popEnterTransition = { fadeIn(tween(dur)) },
                popExitTransition = { if (initialState.destination.route in tabs) fadeOut(tween(dur)) else slideOutHorizontally(tween(dur)) { it / 5 } + fadeOut(tween(dur)) },
            ) {
                composable(Routes.HOME) { HomeScreen(vm, nav) }
                composable(Routes.REPAIRS) { RepairsScreen(vm, nav) }
                composable(Routes.SUPPORT) { SupportScreen(vm, nav) }
                composable(Routes.ACCOUNT) { AccountScreen(vm, nav) }
                composable(Routes.BOOK) { BookingScreen(vm, nav) }
                composable("booked/{id}") { BookedScreen(vm, nav, it.arguments?.getString("id")) }
                composable("track/{id}") { TrackerScreen(vm, nav, it.arguments?.getString("id")) }
                composable("quote/{id}") { QuoteScreen(vm, nav, it.arguments?.getString("id")) }
                composable("invoice/{id}") { InvoiceScreen(vm, nav, it.arguments?.getString("id")) }
                composable("pay/{id}") { PayScreen(vm, nav, it.arguments?.getString("id")) }
                composable(
                    "warranty?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { WarrantyScreen(vm, nav, it.arguments?.getString("id")) }
                composable("claim/{id}") { ClaimScreen(vm, nav, it.arguments?.getString("id")) }
                composable(
                    "report?repair={repair}",
                    arguments = listOf(navArgument("repair") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { ReportScreen(vm, nav, it.arguments?.getString("repair")) }
                composable(Routes.CHAT) { ChatScreen(vm, nav) }
                composable(Routes.HELP) { HelpScreen(nav) }
                composable(Routes.ADDRESSES) { AddressesScreen(vm, nav) }
                composable(Routes.NEW_ADDRESS) { NewAddressScreen(vm, nav) }
                composable(Routes.NOTIFICATIONS) { NotificationsScreen(vm, nav) }
                composable(Routes.PRIVACY) { PrivacyScreen(vm, nav) }
                composable(Routes.PROTOTYPE) { PrototypeScreen(vm, nav) }
                composable(
                    Routes.DIAGNOSTICS_PATTERN,
                    arguments = listOf(
                        navArgument("from") { type = NavType.StringType; defaultValue = CheckOrigin.HOME.name },
                        navArgument("repair") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) {
                    val origin = runCatching { CheckOrigin.valueOf(it.arguments?.getString("from") ?: "") }.getOrDefault(CheckOrigin.HOME)
                    DiagnosticsScreen(vm, nav, origin, it.arguments?.getString("repair"))
                }
            }
        }
        if (route in tabs) {
            BottomNav(
                items = listOf(
                    NavItem(Routes.HOME, "Home"),
                    NavItem(Routes.REPAIRS, "Repairs", badge = vm.repairs.any { it.isActive && it.stage == com.switchon.customer.data.Stage.QUOTE_PENDING || it.needsPayment }),
                    NavItem(Routes.SUPPORT, "Support"),
                    NavItem(Routes.ACCOUNT, "Account"),
                ),
                current = route,
                onSelect = { nav.switchTab(it) },
            )
        }
    }
}

/**
 * Navigate only from a settled screen. A second tap that lands while the first navigation is still
 * animating is dropped, so the same screen can't be pushed twice.
 */
fun NavHostController.go(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navigate(route, builder)
}

/** Back from an on-screen control, guarded the same way so a double tap can't pop two screens. */
fun NavHostController.back(): Boolean =
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack() else false

/**
 * Leaves a flow (booking, claim) for a top-level tab, dropping the flow's screens. Unlike [switchTab] it doesn't
 * restore saved tab state, which would bring the finished flow back.
 */
fun NavHostController.leaveFlowTo(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { inclusive = route == Routes.HOME }
        launchSingleTop = true
    }
}

fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
