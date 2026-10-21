package com.switchon.customer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.switchon.customer.data.DeviceType
import com.switchon.customer.data.IssueType
import com.switchon.customer.ui.components.BottomActions
import com.switchon.customer.ui.components.TopBar
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type

fun deviceIcon(d: DeviceType): ImageVector = when (d) {
    DeviceType.PHONE -> Icons.Outlined.PhoneAndroid
    DeviceType.LAPTOP -> Icons.Outlined.Laptop
    DeviceType.TABLET -> Icons.Outlined.TabletAndroid
    DeviceType.PC -> Icons.Outlined.DesktopWindows
    DeviceType.WEARABLE -> Icons.Outlined.Watch
    DeviceType.AUDIO -> Icons.Outlined.Headphones
}

fun issueIcon(i: IssueType): ImageVector = when (i) {
    IssueType.SCREEN -> Icons.Outlined.BrokenImage
    IssueType.BATTERY -> Icons.Outlined.BatteryAlert
    IssueType.CHARGING -> Icons.Outlined.Power
    IssueType.WATER -> Icons.Outlined.WaterDrop
    IssueType.PERFORMANCE -> Icons.Outlined.Speed
    IssueType.OTHER -> Icons.AutoMirrored.Outlined.HelpOutline
}

/** The Switch On mark: a red switch body with a paper knob, then the wordmark. */
@Composable
fun Logo(modifier: Modifier = Modifier, markWidth: Dp = 34.dp, style: TextStyle = Type.h3, showWord: Boolean = true) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Switch On" }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(markWidth, markWidth * 22f / 38f)) {
            val u = size.width / 38f
            drawRect(SO.red)
            drawRect(SO.paper, topLeft = Offset(20 * u, 4 * u), size = Size(14 * u, 14 * u))
        }
        if (showWord) {
            Spacer(Modifier.width(10.dp))
            Text("SWITCH ON", style = style, color = SO.paper)
        }
    }
}

/**
 * Standard pushed screen: ruled top bar, scrolling body with 16dp gutters, optional sticky bottom actions.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    eyebrow: String? = null,
    bottom: (@Composable () -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(title, onBack, eyebrow = eyebrow, actions = actions)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content()
            Spacer(Modifier.height(16.dp))
            if (bottom == null) Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (bottom != null) BottomActions { bottom() }
    }
}

fun Throwable.friendly(): String = message ?: "Something went wrong. Please try again."
