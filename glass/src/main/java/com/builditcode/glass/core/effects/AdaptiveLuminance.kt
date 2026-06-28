package com.builditcode.glass.core.effects

import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.builditcode.glass.core.AdaptiveLuminanceEffectScope
import kotlin.math.sign

fun AdaptiveLuminanceEffectScope.adaptiveLuminanceGlass(
    lowLuminanceBlurRadius: Float = 2f.dp.toPx(),
    neutralBlurRadius: Float = 8f.dp.toPx(),
    highLuminanceBlurRadius: Float = 16f.dp.toPx(),
    saturation: Float = 1.5f
) {
    val adjustedLuminance = (luminance * 2f - 1f).let { sign(it) * it * it }

    colorControls(
        brightness =
            if (adjustedLuminance > 0f) {
                lerp(0.1f, 0.5f, adjustedLuminance)
            } else {
                lerp(0.1f, -0.2f, -adjustedLuminance)
            },
        contrast =
            if (adjustedLuminance > 0f) {
                lerp(1f, 0f, adjustedLuminance)
            } else {
                1f
            },
        saturation = saturation
    )
    blur(
        if (adjustedLuminance > 0f) {
            lerp(neutralBlurRadius, highLuminanceBlurRadius, adjustedLuminance)
        } else {
            lerp(neutralBlurRadius, lowLuminanceBlurRadius, -adjustedLuminance)
        }
    )
}
