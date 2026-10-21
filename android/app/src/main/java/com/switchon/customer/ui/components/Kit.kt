package com.switchon.customer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.switchon.customer.R
import com.switchon.customer.data.rupees
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type

// ---- Buttons --------------------------------------------------------------------------------------------

enum class BtnKind { PRIMARY, PAPER, OUTLINE, INK, GHOST }

/**
 * Square, uppercase, 56dp. PRIMARY is solid signal red; PAPER is paper fill for strong secondary;
 * OUTLINE is a paper outline for lower-priority actions; INK is for use on paper surfaces.
 */
@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = Icons.AutoMirrored.Outlined.ArrowForward,
    leadingIcon: ImageVector? = null,
    compact: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (bg, fg, border) = when (kind) {
        BtnKind.PRIMARY -> Triple(SO.red, SO.paper, SO.red)
        BtnKind.PAPER -> Triple(SO.paper, SO.ink, SO.paper)
        BtnKind.OUTLINE -> Triple(Color.Transparent, SO.paper, SO.paper)
        BtnKind.INK -> Triple(SO.ink, SO.paper, SO.ink)
        BtnKind.GHOST -> Triple(Color.Transparent, SO.paper, Color.Transparent)
    }
    val active = enabled && !loading
    val bgC = if (!enabled) SO.ink3 else if (pressed && kind == BtnKind.PRIMARY) SO.redDeep else bg
    val fgC = if (!enabled) SO.mute2 else fg
    val bC = if (!enabled) SO.line2 else border
    Box(
        modifier
            .defaultMinSize(minHeight = if (compact) 48.dp else 56.dp)
            .graphicsLayer { translationY = if (pressed) 2.dp.toPx() else 0f }
            .background(bgC)
            .border(2.dp, bC)
            .clickable(source, indication = androidx.compose.material3.ripple(color = fgC), enabled = active, role = Role.Button, onClick = onClick)
            .semantics { if (loading) stateDescription = "Working, please wait" }
            .then(if (loading) Modifier.scanLine(LocalReducedMotion.current, repeat = true, durationMs = 800) else Modifier)
            .padding(horizontal = if (compact) 16.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (leadingIcon != null && !loading) {
                Icon(leadingIcon, null, tint = fgC, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            }
            Text(
                if (loading) "Working…".uppercase() else text.uppercase(),
                style = Type.button.copy(fontSize = if (compact) 13.sp else 15.sp),
                color = fgC, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (icon != null && !loading && kind != BtnKind.GHOST) {
                Spacer(Modifier.width(12.dp)); Icon(icon, null, tint = fgC, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Underlined text action, used sparingly for tertiary actions. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = SO.paper, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = Type.bodyStrong.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = color)
    }
}

/** Square icon button, 48dp target, outlined. */
@Composable
fun SquareIconBtn(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = SO.paper, border: Color = SO.line2) {
    Box(
        modifier.size(48.dp).border(1.dp, border).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

// ---- Chips (selectable tiles) ---------------------------------------------------------------------------

/**
 * Square selectable tile. Selected = signal-red fill with a hard paper offset, exactly like the site's
 * device chooser. No pills.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    enabled: Boolean = true,
    tall: Boolean = false,
    /** Stretch to the row's height; use inside a Row with height(IntrinsicSize.Min) so a grid row stays even. */
    fillHeight: Boolean = false,
) {
    val offset by animateDpAsState(if (selected) 5.dp else 0.dp, tween(140), label = "chipOff")
    Box(modifier.padding(end = 5.dp, bottom = 5.dp)) {
        if (selected) Box(Modifier.matchParentSize().offset(offset, offset).background(SO.paper))
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .heightIn(min = if (tall) 92.dp else 52.dp)
                .background(if (selected) SO.red else SO.ink2)
                .border(1.dp, if (selected) SO.red else if (enabled) SO.line2 else SO.line)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, if (tall) Alignment.Bottom else Alignment.CenterVertically),
        ) {
            if (icon != null) Icon(icon, null, tint = if (!enabled) SO.line2 else if (selected) SO.paper else SO.mute, modifier = Modifier.size(24.dp))
            Text(
                if (tall) label.uppercase() else label,
                style = if (tall) Type.h3 else Type.bodyStrong,
                color = if (!enabled) SO.mute2 else SO.paper, maxLines = 2,
            )
            if (sub != null) Text(sub, style = Type.small, color = if (!enabled) SO.mute2 else if (selected) SO.paper else SO.mute)
        }
    }
}

/** Multi-select tag (square, mono). */
@Composable
fun Tag(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) SO.paper else Color.Transparent)
            .border(1.dp, if (selected) SO.paper else SO.line2)
            .toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (selected) SO.ink else SO.paper)
    }
}

/** Mono status label in a ruled box. */
@Composable
fun StatusTag(text: String, color: Color = SO.paper, fill: Color = Color.Transparent, modifier: Modifier = Modifier) {
    Box(modifier.background(fill).border(1.dp, color).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text.uppercase(), style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = color, maxLines = 1)
    }
}

// ---- Toggle: the logo, as a switch ----------------------------------------------------------------------

/** Square switch drawn like the Switch On mark: red body with a paper knob when on. */
@Composable
fun SwitchToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, sub: String? = null, modifier: Modifier = Modifier) {
    val knobX by animateDpAsState(if (checked) 24.dp else 4.dp, tween(140), label = "knob")
    val body by animateColorAsState(if (checked) SO.red else SO.ink3, tween(140), label = "body")
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = Type.bodyStrong, color = SO.paper)
            if (sub != null) Text(sub, style = Type.small, color = SO.mute)
        }
        Box(Modifier.size(width = 48.dp, height = 28.dp).background(body).border(1.dp, if (checked) SO.red else SO.line2)) {
            Box(Modifier.offset(x = knobX, y = 4.dp).size(20.dp).background(if (checked) SO.paper else SO.mute2))
        }
    }
}

// ---- Text field -----------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    prefix: String? = null,
    style: TextStyle = Type.bodyL,
    imeDone: (() -> Unit)? = null,
    maxLength: Int? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val bringIntoView = remember { BringIntoViewRequester() }
    // Show the keyboard explicitly on focus (some devices and emulators don't on their own), then keep the
    // field visible once the keyboard has finished sliding up and the screen has resized around it.
    LaunchedEffect(focused) {
        if (focused) {
            keyboardController?.show()
            delay(350)
            bringIntoView.bringIntoView()
        }
    }
    Column(modifier.bringIntoViewRequester(bringIntoView), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label.uppercase(), style = Type.label, color = if (error != null) SO.redHot else SO.mute, modifier = Modifier.weight(1f))
            if (maxLength != null) Text("${value.length}/$maxLength", style = Type.label, color = if (value.length > maxLength) SO.redHot else SO.mute2)
        }
        BasicTextField(
            value = value,
            onValueChange = { v -> onChange(if (maxLength != null) v.take(maxLength) else v) },
            singleLine = singleLine,
            minLines = minLines,
            textStyle = style.copy(color = SO.paper),
            cursorBrush = SolidColor(SO.redHot),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                capitalization = capitalization,
                imeAction = if (imeDone != null) ImeAction.Done else if (singleLine) ImeAction.Next else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { imeDone?.invoke() ?: keyboardController?.hide() }),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                if (error != null) error(error)
            },
            decorationBox = { inner ->
                // No clickable here: the text field's own tap handling is what raises the keyboard.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(SO.ink2)
                        .border(if (focused || error != null) 2.dp else 1.dp, if (error != null) SO.redHot else if (focused) SO.paper else SO.line2)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (prefix != null) {
                        Text(prefix, style = style, color = SO.mute); Spacer(Modifier.width(10.dp))
                        Box(Modifier.width(1.dp).height(24.dp).background(SO.line2)); Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = style, color = SO.mute2)
                        inner()
                    }
                }
            },
        )
        if (error != null) Text(error, style = Type.small, color = SO.redHot)
    }
}

// ---- Headings -------------------------------------------------------------------------------------------

@Composable
fun Display(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.h1, color: Color = SO.paper) {
    Text(text.uppercase(), style = style, color = color, modifier = modifier.semantics { heading() })
}

/** Large condensed price, with an optional small lead word like "FROM". */
@Composable
fun Price(amount: Int, lead: String? = null, color: Color = LocalContentColor.current, style: TextStyle = Type.price, leadColor: Color = color) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (lead != null) {
            Text(lead.uppercase(), style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = leadColor, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(rupees(amount), style = style, color = color)
    }
}

// ---- Top bar & screen scaffolding -----------------------------------------------------------------------

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, eyebrow: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().background(SO.ink)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                SquareIconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
            } else Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                if (eyebrow != null) Text(eyebrow.uppercase(), style = Type.label, color = SO.mute)
                Text(title.uppercase(), style = Type.h3, color = SO.paper, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            }
            actions()
        }
        Rule()
    }
}

/** Sticky footer for screens with a main action: ruled, ink, above the nav bar. */
@Composable
fun BottomActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(SO.ink).windowInsetsPadding(WindowInsets.navigationBars)) {
        Rule()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

// ---- Bottom navigation ----------------------------------------------------------------------------------

data class NavItem(val route: String, val label: String, val badge: Boolean = false)

/** Minimal, dark, squared, label-led. Signal red marks the active tab. */
@Composable
fun BottomNav(items: List<NavItem>, current: String?, onSelect: (String) -> Unit) {
    // Four labels must share the width: let them grow with the font scale only up to 1.2x, and start a
    // step smaller on narrow phones (under 400dp).
    val narrow = LocalConfiguration.current.screenWidthDp < 400
    val labelSize = ((if (narrow) 11f else 12f) * minOf(1f, 1.2f / LocalDensity.current.fontScale)).sp
    Column(Modifier.fillMaxWidth().background(SO.ink).windowInsetsPadding(WindowInsets.navigationBars)) {
        Rule(SO.line2)
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            items.forEachIndexed { i, item ->
                val on = item.route == current
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(on, role = Role.Tab, onClick = { onSelect(item.route) })
                        .semantics(mergeDescendants = true) { contentDescription = item.label + if (item.badge) ", needs attention" else "" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(SO.red))
                    if (i > 0) Box(Modifier.align(Alignment.CenterStart).width(1.dp).height(24.dp).background(SO.line))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) { Tick(SO.redHot, 6.dp); Spacer(Modifier.width(if (narrow) 4.dp else 7.dp)) }
                        Text(
                            item.label.uppercase(),
                            style = Type.label.copy(fontSize = labelSize, letterSpacing = if (narrow) 0.5.sp else 1.sp, fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium),
                            color = if (on) SO.paper else SO.mute, maxLines = 1,
                        )
                        if (item.badge) { Spacer(Modifier.width(if (narrow) 3.dp else 6.dp)); Box(Modifier.size(7.dp).background(SO.redHot)) }
                    }
                }
            }
        }
    }
}

// ---- States ---------------------------------------------------------------------------------------------

@Composable
fun OfflineBanner(onRetry: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(SO.paper).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CloudOff, null, tint = SO.red, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("OFFLINE", style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = SO.red)
            Text("Bookings, approvals and payments will wait until you reconnect.", style = Type.small, color = SO.ink)
        }
        if (onRetry != null) TextAction("Retry", onRetry, SO.ink)
    }
}

@Composable
fun ErrorPanel(title: String, message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, retryLabel: String = "Try again") {
    Column(
        modifier.fillMaxWidth().border(2.dp, SO.redHot).background(SO.ink2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = SO.redHot, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(title.uppercase(), style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = SO.redHot)
        }
        Text(message, style = Type.body, color = SO.paper)
        if (onRetry != null) Btn(retryLabel, onRetry, kind = BtnKind.OUTLINE, compact = true, icon = null)
    }
}

@Composable
fun EmptyState(code: String, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().border(1.dp, SO.line2).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(code.uppercase(), style = Type.label, color = SO.mute)
        Display(title, style = Type.h2)
        Text(body, style = Type.body, color = SO.mute)
        if (action != null) { Spacer(Modifier.height(4.dp)); action() }
    }
}

/** Skeleton block for loading states: flat ink3 with a slow scan line (still if reduced motion). */
@Composable
fun Skeleton(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(height).background(SO.ink3)
            .scanLine(LocalReducedMotion.current, repeat = true, durationMs = 1300)
    )
}

@Composable
fun LoadingList(label: String = "Fetching your repairs", rows: Int = 3) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label.uppercase(), style = Type.label, color = SO.mute)
        ScanBar()
        repeat(rows) { Skeleton(112.dp) }
    }
}

// ---- Dialog ---------------------------------------------------------------------------------------------

/** Squared dialog on ink with a paper rule; replaces the stock AlertDialog. */
@Composable
fun SoDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Not now",
    danger: Boolean = false,
    loading: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        // Scrolls when large text or a short screen would otherwise push the buttons out of reach.
        Column(
            Modifier.fillMaxWidth().background(SO.ink2).border(2.dp, SO.paper).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Display(title, style = Type.h2)
            Text(body, style = Type.body, color = SO.mute)
            extra?.invoke()
            Btn(confirm, onConfirm, Modifier.fillMaxWidth(), kind = if (danger) BtnKind.PAPER else BtnKind.PRIMARY, loading = loading, icon = null)
            Btn(dismiss, onDismiss, Modifier.fillMaxWidth(), kind = BtnKind.OUTLINE, enabled = !loading, icon = null)
        }
    }
}

// ---- Octopus artwork (the only illustration) ------------------------------------------------------------

/**
 * Mobile hero from the site: paper slab with a dot grid and a red top bar, an ink disc behind the octopus,
 * and a "Fig. 01" tag. Cropped so the technician fills the frame.
 */
@Composable
fun OctoHero(modifier: Modifier = Modifier, height: Dp = 260.dp, tag: String = "Fig. 01 / Technician on call") {
    val reduced = LocalReducedMotion.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(SO.paper)
            .dotGrid()
            .sweep(reduced),
    ) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(10.dp).background(SO.red))
        Box(
            Modifier.align(Alignment.Center).offset(y = 8.dp).size(height * 0.86f).clip(CircleShape).background(SO.ink)
        )
        Image(
            painterResource(R.drawable.octopus), contentDescription = "Switch On's octopus technician holding a heat gun, a cracked phone and repair tools",
            contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 2.dp).requiredSize(height * 1.12f),
        )
        Box(Modifier.padding(start = 12.dp, top = 22.dp).background(SO.paper).border(1.dp, SO.ink).padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(tag.uppercase(), style = Type.label.copy(fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = SO.ink)
        }
    }
}

/** Tight crop on the technician's face (matches the site's .face avatar), for the active-repair card. */
@Composable
fun OctoFace(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clipToBounds().background(SO.ink).border(1.dp, SO.line2)) {
        Image(
            painterResource(R.drawable.octopus), contentDescription = null, contentScale = ContentScale.Crop,
            // Centred by requiredSize, then nudged to the site crop: 285% wide, shifted -92% / -29.5%.
            modifier = Modifier.requiredSize(size * 2.85f).offset(x = size * 0.005f, y = size * 0.63f),
        )
    }
}

/** Placeholder for a handover photo: framed ink tile with the device outline and the capture stamp. */
@Composable
fun HandoverTile(caption: String, meta: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.border(1.dp, SO.line2).background(SO.ink2)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(SO.ink3).dotGrid(SO.line2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SO.mute, modifier = Modifier.size(40.dp))
            Box(Modifier.align(Alignment.BottomStart).background(SO.ink).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text("● REC", style = Type.label.copy(fontSize = 9.sp), color = SO.redHot)
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(caption, style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = SO.paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = Type.label.copy(fontSize = 9.sp, letterSpacing = 0.6.sp), color = SO.mute, maxLines = 1)
        }
    }
}

// ---- Misc -----------------------------------------------------------------------------------------------

/** Segmented progress for multi-step flows: one square segment per step, filled red up to the current one. */
@Composable
fun StepBar(step: Int, total: Int, label: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Step $step of $total: $label" },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("STEP %02d / %02d".format(step, total), style = Type.label, color = SO.paper)
            Text(label.uppercase(), style = Type.label, color = SO.mute)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { i ->
                Box(Modifier.weight(1f).height(6.dp).background(if (i < step) SO.red else SO.line))
            }
        }
    }
}

/** Ink panel with a hairline border: the dark counterpart to the paper docket. */
@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = SO.line2, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(SO.ink2).border(1.dp, border)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { CompositionLocalProvider(LocalContentColor provides SO.paper) { content() } }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = SO.paper, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(key.uppercase(), style = Type.label, color = SO.mute, modifier = Modifier.width(120.dp).padding(top = 3.dp, end = 10.dp))
        Text(value, style = if (mono) Type.mono else Type.body, color = valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(title)
        content()
    }
}

// ---- Number pad ---------------------------------------------------------------------------------------

/**
 * On-screen number pad under a mobile-number field. Always visible, so input never depends on a software keyboard. Works where no software or hardware keyboard reaches the app
 * (some emulators); the text field still accepts typed input when a keyboard is available.
 */
@Composable
fun NumberPad(onDigit: (String) -> Unit, onDelete: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(CLEAR, "0", DELETE))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    when (key) {
                        CLEAR -> KeypadKey("CLEAR", "Clear number", onClear, Modifier.weight(1f), small = true)
                        DELETE -> KeypadKey("DEL", "Delete last digit", onDelete, Modifier.weight(1f), small = true, icon = Icons.AutoMirrored.Outlined.Backspace)
                        else -> KeypadKey(key, "Digit $key", { onDigit(key) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private const val CLEAR = "pad-clear"
private const val DELETE = "pad-delete"

/** Square key: ink with a paper border; signal red while pressed. Digits in mono. */
@Composable
private fun KeypadKey(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    icon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(if (pressed) SO.red else SO.ink)
            .border(1.dp, if (pressed) SO.red else SO.paper)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = SO.paper, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(
                label,
                style = if (small) Type.label.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else Type.monoL.copy(fontSize = 22.sp),
                color = SO.paper,
            )
        }
    }
}
