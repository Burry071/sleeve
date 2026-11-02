package com.junkfood.seal.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Press feedback: subtle scale-down while touched (emil-design-eng 100-160ms).
 * Pass the SAME interaction source the clickable/button uses — a source nothing
 * feeds never reports presses.
 */
fun Modifier.pressScale(interactionSource: MutableInteractionSource? = null): Modifier =
    composed {
        val source = interactionSource ?: remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val scale by
            animateFloatAsState(
                targetValue = if (pressed) 0.97f else 1f,
                animationSpec = tween(durationMillis = 160),
                label = "pressScale",
            )
        this.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }
