package com.switchon.customer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switchon.customer.data.AppViewModel
import com.switchon.customer.ui.components.Btn
import com.switchon.customer.ui.components.Display
import com.switchon.customer.ui.components.ErrorPanel
import com.switchon.customer.ui.components.Eyebrow
import com.switchon.customer.ui.components.Field
import com.switchon.customer.ui.components.LocalReducedMotion
import com.switchon.customer.ui.components.OctoHero
import com.switchon.customer.ui.components.Rule
import com.switchon.customer.ui.components.ScanBar
import com.switchon.customer.ui.components.SquareIconBtn
import com.switchon.customer.ui.components.TextAction
import com.switchon.customer.ui.components.scanLine
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AuthStep { SPLASH, INTRO }

@Composable
fun AuthFlow(vm: AppViewModel) {
    // Splash only on a cold start; after logout the customer lands straight on onboarding.
    var step by rememberSaveable { mutableStateOf(if (vm.introSeen) AuthStep.INTRO else AuthStep.SPLASH) }
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        step,
        transitionSpec = { fadeIn(tween(if (reduced) 0 else 240)) togetherWith fadeOut(tween(if (reduced) 0 else 160)) },
        label = "authStep",
        modifier = Modifier.fillMaxSize().background(SO.ink),
    ) { s ->
        when (s) {
            AuthStep.SPLASH -> Splash { step = AuthStep.INTRO }
            AuthStep.INTRO -> Intro(vm::continueAsGuest)
        }
    }
}

@Composable
private fun Splash(done: () -> Unit) {
    val reduced = LocalReducedMotion.current
    LaunchedEffect(Unit) { delay(if (reduced) 700 else 1700); done() }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Logo(markWidth = 56.dp, style = Type.h1)
            Column(Modifier.fillMaxWidth().scanLine(reduced, durationMs = 1100)) {
                Rule(SO.paper, 3.dp)
                Spacer(Modifier.height(16.dp))
                Display("Know the fault.", style = Type.hero)
                Display("Know the price.", style = Type.hero, color = SO.redHot)
            }
            Text("DOORSTEP ELECTRONICS REPAIR · INDIA", style = Type.label, color = SO.mute)
        }
        ScanBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 24.dp, end = 24.dp))
    }
}

@Composable
private fun Intro(continueAsGuest: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo()
            }
            OctoHero(height = 250.dp)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Eyebrow("Doorstep repair")
                Column {
                    Display("Know the fault.", style = Type.hero)
                    Display("Know the price.", style = Type.hero, color = SO.redHot)
                }
                Text(
                    "Phones, laptops, tablets and more, picked up from your door, fixed by a verified shop and brought back sealed.",
                    style = Type.bodyL, color = SO.mute,
                )
                Column(Modifier.border(1.dp, SO.line2)) {
                    Promise("01", "Base price before you book", "Every estimate is published. No surprises.")
                    Rule(SO.line2)
                    Promise("02", "Nothing starts without your OK", "You approve the final quote in the app.")
                    Rule(SO.line2)
                    Promise("03", "Pay after it's back", "UPI, card or cash on delivery.")
                }
            }
        }
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            Rule()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Continue as guest", continueAsGuest, Modifier.fillMaxWidth())
                Text(
                    "No sign-in required for this early-access app. You'll add contact details only when you book a pickup.",
                    style = Type.small, color = SO.mute, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Promise(n: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
        Text(n, style = Type.h1.copy(fontSize = 36.sp, lineHeight = 32.sp), color = SO.redHot, modifier = Modifier.width(52.dp))
        Column {
            Text(title, style = Type.bodyStrong, color = SO.paper)
            Text(body, style = Type.small, color = SO.mute)
        }
    }
}
