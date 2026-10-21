package com.switchon.customer.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.switchon.customer.ui.theme.SO
import com.switchon.customer.ui.theme.Type

// ---- Motion ---------------------------------------------------------------------------------------------

/** True when the system "Remove animations" setting is on. Every decorative animation checks this. */
val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

// ---- Torn paper -----------------------------------------------------------------------------------------

/**
 * Paper with a saw-tooth tear along the bottom (and optionally the top), like the site's quote docket:
 * teeth [toothPx] wide and half as deep.
 */
fun tornShape(toothPx: Float, top: Boolean = false): Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val step = w / (w / toothPx).toInt().coerceAtLeast(1)
    val depth = step / 2f
    moveTo(0f, if (top) depth else 0f)
    if (top) {
        var x = 0f
        while (x < w - 0.5f) {
            lineTo(x + step / 2f, 0f); lineTo(x + step, depth); x += step
        }
    } else lineTo(w, 0f)
    lineTo(w, h - depth)
    var x = w
    while (x > 0.5f) {
        lineTo(x - step / 2f, h); lineTo(x - step, h - depth); x -= step
    }
    close()
}

@Composable
fun rememberTornShape(top: Boolean = false): Shape {
    val px = with(LocalDensity.current) { 18.dp.toPx() }
    return remember(px, top) { tornShape(px, top) }
}

/**
 * The paper docket. Estimates, quotes, invoices and repair summaries all sit on this surface.
 * Content inside reads as ink on paper.
 */
@Composable
fun Docket(
    modifier: Modifier = Modifier,
    torn: Boolean = true,
    tornTop: Boolean = false,
    shadow: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (torn) rememberTornShape(tornTop) else androidx.compose.ui.graphics.RectangleShape
    val bottomPad = if (torn) 30.dp else 20.dp
    Box(modifier) {
        // Hard offset shadow, kept inside the bounds so clipping modifiers (scan line) don't cut it off.
        if (shadow != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(start = 8.dp, top = 8.dp)
                    .graphicsLayer { this.shape = shape; clip = true }
                    .background(shadow)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (shadow != null) Modifier.padding(end = 8.dp, bottom = 8.dp) else Modifier)
                .graphicsLayer { this.shape = shape; clip = true }
                .background(SO.paper)
                .padding(start = 20.dp, end = 20.dp, top = if (tornTop) 30.dp else 20.dp, bottom = bottomPad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides SO.ink) { content() }
        }
    }
}

/** Docket header: two mono labels over a heavy ink rule. */
@Composable
fun DocketHead(left: String, right: String? = null, color: Color = SO.ink) {
    Column {
        // Both labels may wrap at large font sizes; they never overlap.
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(left.uppercase(), style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = color, modifier = Modifier.weight(1f))
            if (right != null) {
                Spacer(Modifier.width(12.dp))
                Text(right.uppercase(), style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = color,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(color))
    }
}

/** A labelled row on paper: mono label above, value below. */
@Composable
fun DocketField(label: String, value: String, modifier: Modifier = Modifier, valueStyle: androidx.compose.ui.text.TextStyle = Type.bodyStrong) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), style = Type.label, color = SO.pencil)
        Text(value, style = valueStyle, color = SO.ink)
    }
}

/** Receipt line: description left, amount right, dotted leader feel via spacing. */
@Composable
fun ReceiptLine(label: String, detail: String?, amount: String, strong: Boolean = false, color: Color = SO.ink) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = if (strong) Type.bodyStrong else Type.body, color = color)
            if (detail != null) Text(detail, style = Type.small, color = if (color == SO.ink) SO.pencil else SO.mute)
        }
        Text(amount, style = if (strong) Type.monoL else Type.mono, color = color, textAlign = TextAlign.End)
    }
}

@Composable
fun DashedRule(color: Color = SO.ink, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(1.dp).drawBehind {
            drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        }
    )
}

@Composable
fun Rule(color: Color = SO.line2, thickness: Dp = 1.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}

// ---- Stamp ----------------------------------------------------------------------------------------------

/**
 * Rotated double-outline rubber stamp, as on the site. On first composition it thumps in from 1.7× scale
 * unless reduced motion is on.
 */
@Composable
fun Stamp(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = SO.red,
    rotation: Float = -5f,
    big: Boolean = false,
    animate: Boolean = true,
    /** Solid backing, for a stamp laid over artwork. */
    fill: Color? = null,
) {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(if (animate && !reduced) 1.7f else 1f) }
    val alpha = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate && !reduced) {
            delay(180)
            launch { alpha.animateTo(1f, tween(120)) }
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 900f))
        }
    }
    Box(
        modifier
            .semantics { contentDescription = text }
            .graphicsLayer { rotationZ = rotation; scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .border(1.dp, color)
            .padding(3.dp)
            .border(2.dp, color)
            .padding(horizontal = if (big) 14.dp else 10.dp, vertical = if (big) 8.dp else 5.dp)
    ) {
        Text(
            text.uppercase(),
            style = if (big) Type.monoL.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2.4f, androidx.compose.ui.unit.TextUnitType.Sp))
            else Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = color,
            maxLines = 1,
        )
    }
}

// ---- Scan -----------------------------------------------------------------------------------------------

/**
 * A red diagnostic scan line sweeping down over the content, with a faint trailing glow.
 * [repeat] keeps it looping (used while a request is in flight); otherwise it runs once on entry.
 */
fun Modifier.scanLine(reduced: Boolean, repeat: Boolean = false, durationMs: Int = 900, key: Any? = Unit): Modifier =
    if (reduced) this else composedScan(repeat, durationMs, key)

@Composable
private fun scanProgress(repeat: Boolean, durationMs: Int, key: Any?): Float {
    if (repeat) {
        val t = rememberInfiniteTransition(label = "scan")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart), label = "scanP")
        return p
    }
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.snapTo(0f); delay(250); a.animateTo(1f, tween(durationMs, easing = LinearEasing)) }
    return a.value
}

private fun Modifier.composedScan(repeat: Boolean, durationMs: Int, key: Any?): Modifier = composed {
    val p = scanProgress(repeat, durationMs, key)
    this.clipToBounds().drawWithContent {
        drawContent()
        if (p <= 0f || p >= 1f) return@drawWithContent
        val y = size.height * p
        val glow = 36.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, SO.redHot.copy(alpha = 0.16f)), startY = y - glow, endY = y),
            topLeft = Offset(0f, y - glow), size = Size(size.width, glow),
        )
        drawRect(SO.redHot, topLeft = Offset(0f, y - 1.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
    }
}

/** Horizontal sweep used over the hero art: a red-edged band crossing left to right once. */
fun Modifier.sweep(reduced: Boolean): Modifier = if (reduced) this else composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(600); a.animateTo(1f, tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val p = a.value
    this.drawWithContent {
        drawContent()
        if (p <= 0f || p >= 1f) return@drawWithContent
        val band = 90.dp.toPx()
        val x = -band + (size.width + band) * p
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, SO.redHot.copy(alpha = 0.16f)), startX = x, endX = x + band),
            topLeft = Offset(x, 0f), size = Size(band, size.height),
        )
        drawRect(SO.redHot, topLeft = Offset(x + band - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
    }
}

/** A diagnostic "reading" bar, used as a loading indicator in place of spinners. */
@Composable
fun ScanBar(modifier: Modifier = Modifier, color: Color = SO.redHot, track: Color = SO.line) {
    val reduced = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "bar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "barP")
    Box(
        modifier.fillMaxWidth().height(4.dp).background(track).drawBehind {
            if (reduced) {
                drawRect(color, size = Size(size.width * 0.4f, size.height))
            } else {
                val w = size.width * 0.32f
                val x = -w + (size.width + w) * p
                drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            }
        }
    )
}

/** Small hard-edged square used as a bullet, as in the site's eyebrows. */
@Composable
fun Tick(color: Color = SO.redHot, size: Dp = 8.dp) {
    Box(Modifier.width(size).height(size).background(color))
}

@Composable
fun Eyebrow(text: String, color: Color = SO.paper, mark: Color = SO.redHot, rule: Color = SO.line2, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Tick(mark, 10.dp)
        Spacer(Modifier.width(12.dp))
        Text(text.uppercase(), style = Type.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(rule))
    }
}

/** Dotted-paper backdrop from the mobile hero on the site. */
fun Modifier.dotGrid(dot: Color = SO.ink.copy(alpha = 0.22f)): Modifier = this.drawBehind {
    val step = 14.dp.toPx()
    val r = 1.2.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(dot, r, Offset(x, y)); x += step }
        y += step
    }
}

/** Hairline frame drawn inside the bounds (keeps layout size stable). */
fun Modifier.frame(color: Color, width: Dp = 1.dp): Modifier = this.drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
}
