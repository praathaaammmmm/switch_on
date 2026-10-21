@file:OptIn(ExperimentalTextApi::class)

package com.switchon.customer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switchon.customer.R

/** Brand tokens, shared with the Switch On landing page (index.html :root). One dark look by design. */
object SO {
    val ink = Color(0xFF070606)
    val ink2 = Color(0xFF0F0C0C)
    val ink3 = Color(0xFF191415)
    val line = Color(0xFF2C2324)
    val line2 = Color(0xFF54464A)
    val red = Color(0xFFD4122A)
    val redHot = Color(0xFFFF2D45)
    val redDeep = Color(0xFF8E0B1B)
    val paper = Color(0xFFF3EEE6)
    val paper2 = Color(0xFFDFD7CB)
    val pencil = Color(0xFF554B48)
    val mute = Color(0xFFABA29E)
    val mute2 = Color(0xFF8C8282)
}

private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** Archivo squeezed to its narrowest width: the bold condensed caps from the brand. */
val Display = FontFamily(archivo(900, 62f), archivo(800, 62f), archivo(700, 75f))

val Body = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            R.font.instrument_sans,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.width(100f)),
        )
    }
)

val Mono = FontFamily(
    listOf(400, 500, 700).map { w ->
        Font(R.font.jetbrains_mono, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

object Type {
    val hero = TextStyle(fontFamily = Display, fontWeight = FontWeight(900), fontSize = 56.sp, lineHeight = 50.sp, letterSpacing = (-0.5).sp)
    val h1 = TextStyle(fontFamily = Display, fontWeight = FontWeight(900), fontSize = 40.sp, lineHeight = 38.sp)
    val h2 = TextStyle(fontFamily = Display, fontWeight = FontWeight(900), fontSize = 30.sp, lineHeight = 30.sp)
    val h3 = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 23.sp, lineHeight = 24.sp)
    val price = TextStyle(fontFamily = Display, fontWeight = FontWeight(900), fontSize = 34.sp, lineHeight = 34.sp)
    val bodyL = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp)
    val small = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val button = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.9.sp)
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.4.sp)
    val monoL = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 1.sp)
}

private val scheme: ColorScheme = darkColorScheme(
    primary = SO.red,
    onPrimary = SO.paper,
    secondary = SO.paper,
    onSecondary = SO.ink,
    background = SO.ink,
    onBackground = SO.paper,
    surface = SO.ink2,
    onSurface = SO.paper,
    surfaceVariant = SO.ink3,
    onSurfaceVariant = SO.mute,
    surfaceContainer = SO.ink2,
    surfaceContainerHigh = SO.ink3,
    surfaceContainerHighest = SO.ink3,
    surfaceContainerLow = SO.ink2,
    outline = SO.line2,
    outlineVariant = SO.line,
    error = SO.redHot,
    onError = SO.ink,
)

@Composable
fun SwitchOnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = Type.bodyL, bodyMedium = Type.body, bodySmall = Type.small,
            labelLarge = Type.button, labelMedium = Type.label, labelSmall = Type.label,
            titleLarge = Type.h3, titleMedium = Type.bodyStrong, titleSmall = Type.bodyStrong,
            headlineSmall = Type.h3, headlineMedium = Type.h2, headlineLarge = Type.h1,
        ),
        // Hard edges throughout, like the paper dockets on the landing page.
        shapes = Shapes(
            extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(2.dp), medium = RoundedCornerShape(2.dp),
            large = RoundedCornerShape(2.dp), extraLarge = RoundedCornerShape(2.dp),
        ),
        content = content,
    )
}
