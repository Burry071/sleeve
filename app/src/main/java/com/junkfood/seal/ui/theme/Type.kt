@file:OptIn(ExperimentalTextApi::class, ExperimentalTextApi::class, ExperimentalTextApi::class)

package com.junkfood.seal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection

// Title weights follow Google's Now in Android reference app: Bold titles,
// Medium labels, Normal body — a three-step hierarchy instead of one flat weight.
val Typography =
    Typography().run {
        copy(
            bodyLarge = bodyLarge.applyLinebreak().applyTextDirection(),
            bodyMedium = bodyMedium.applyLinebreak().applyTextDirection(),
            bodySmall = bodySmall.applyLinebreak().applyTextDirection(),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold).applyTextDirection(),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold).applyTextDirection(),
            titleSmall = titleSmall.applyTextDirection(),
            headlineSmall = headlineSmall.applyTextDirection(),
            headlineMedium = headlineMedium.applyTextDirection(),
            headlineLarge = headlineLarge.applyTextDirection(),
            displaySmall = displaySmall.applyTextDirection(),
            displayMedium = displayMedium.applyTextDirection(),
            displayLarge = displayLarge.applyTextDirection(),
            labelLarge = labelLarge.applyTextDirection(),
            labelMedium = labelMedium.applyTextDirection(),
            labelSmall = labelSmall.applyTextDirection(),
        )
    }

private fun TextStyle.applyLinebreak(): TextStyle = this.copy(lineBreak = LineBreak.Paragraph)

private fun TextStyle.applyTextDirection(): TextStyle =
    this.copy(textDirection = TextDirection.Content)
