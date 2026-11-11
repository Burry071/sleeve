@file:OptIn(ExperimentalTextApi::class)

package com.junkfood.seal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import com.junkfood.seal.R

// Sleeve's own voice: Space Grotesk (OFL) — technical, slightly squared
// letterforms that fit a speed/size-driven downloader.
val SleeveFontFamily =
    FontFamily(
        Font(
            R.font.space_grotesk,
            weight = FontWeight.Normal,
            variationSettings =
                FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.space_grotesk,
            weight = FontWeight.Medium,
            variationSettings =
                FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.space_grotesk,
            weight = FontWeight.SemiBold,
            variationSettings =
                FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            R.font.space_grotesk,
            weight = FontWeight.Bold,
            variationSettings =
                FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

// Title weights follow Google's Now in Android reference app: Bold titles,
// Medium labels, Normal body — a three-step hierarchy instead of one flat weight.
val Typography =
    Typography().run {
        copy(
            bodyLarge = bodyLarge.applyLinebreak().applyTextDirection(),
            bodyMedium = bodyMedium.applyLinebreak().applyTextDirection(),
            bodySmall = bodySmall.applyLinebreak().applyTextDirection(),
            displayLarge = displayLarge.brandFont(),
            displayMedium = displayMedium.brandFont(),
            displaySmall = displaySmall.brandFont(),
            headlineLarge = headlineLarge.brandFont(),
            headlineMedium = headlineMedium.brandFont(),
            headlineSmall = headlineSmall.brandFont(),
            titleLarge = titleLarge.brandFont().copy(fontWeight = FontWeight.Bold),
            titleMedium = titleMedium.brandFont().copy(fontWeight = FontWeight.Bold),
            titleSmall = titleSmall.brandFont().copy(fontWeight = FontWeight.SemiBold),
            labelLarge = labelLarge.brandFont().copy(fontWeight = FontWeight.Medium),
            labelMedium = labelMedium.brandFont().copy(fontWeight = FontWeight.Medium),
            labelSmall = labelSmall.brandFont().copy(fontWeight = FontWeight.Medium),
        )
    }

// Headings + numbers carry the brand face; body stays on the platform sans
// so long text keeps maximum readability.
private fun TextStyle.brandFont(): TextStyle = copy(fontFamily = SleeveFontFamily)

private fun TextStyle.applyLinebreak(): TextStyle = this.copy(lineBreak = LineBreak.Paragraph)

private fun TextStyle.applyTextDirection(): TextStyle =
    this.copy(textDirection = TextDirection.Content)
