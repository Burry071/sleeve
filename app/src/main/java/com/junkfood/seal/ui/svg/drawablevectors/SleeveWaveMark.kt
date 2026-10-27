package com.junkfood.seal.ui.svg.drawablevectors

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Sleeve's brand mark: the S-curl wave from the launcher icon, with two echo
 * waves fading out below — drawn large for empty states and the drawer.
 */
fun sleeveWaveMark(tint: Color): ImageVector =
    ImageVector.Builder(
        name = "SleeveWaveMark",
        defaultWidth = 240.dp,
        defaultHeight = 200.dp,
        viewportWidth = 240f,
        viewportHeight = 200f,
    ).apply {
            // Main curl (launcher geometry, translated to center)
            path(
                stroke = SolidColor(tint),
                strokeLineWidth = 14f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            ) {
                moveTo(83f, 62f)
                curveTo(47f, 44f, 21f, 76f, 53f, 96f)
                curveTo(85f, 116f, 71f, 148f, 31f, 134f)
            }
            // Bubble dot
            path(
                fill = SolidColor(tint),
            ) {
                moveTo(127f, 55f)
                arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
                arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
                close()
            }
            // Echo wave 1
            path(
                stroke = SolidColor(tint),
                strokeLineWidth = 9f,
                strokeAlpha = 0.45f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(150f, 108f)
                curveTo(190f, 118f, 196f, 88f, 168f, 80f)
            }
            // Echo wave 2
            path(
                stroke = SolidColor(tint),
                strokeLineWidth = 7f,
                strokeAlpha = 0.22f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(140f, 140f)
                curveTo(186f, 156f, 216f, 128f, 200f, 96f)
            }
    }.build()
