package com.builditcode.glass.core.effects

import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.builditcode.glass.core.AdaptiveLuminanceEffectScope
import com.builditcode.glass.core.BackdropEffectScope
import kotlin.math.sign

private const val NeutralLuminanceBrightness = 0.1f
private const val HighLuminanceBrightness = 0.5f
private const val LowLuminanceBrightness = -0.2f
private const val NeutralLuminanceContrast = 1f
private const val HighLuminanceContrast = 0f
private const val DefaultAdaptiveSaturation = 1.3f

fun AdaptiveLuminanceEffectScope.adaptiveLuminanceGlass(
    lowLuminanceBlurRadius: Float = 2f.dp.toPx(),
    neutralBlurRadius: Float = 6f.dp.toPx(),
    highLuminanceBlurRadius: Float = 8f.dp.toPx(),
    saturation: Float = DefaultAdaptiveSaturation
) {
    if (!hasLuminanceSample) {
        colorControls(
            brightness = NeutralLuminanceBrightness,
            contrast = NeutralLuminanceContrast,
            saturation = saturation
        )
        blur(neutralBlurRadius)
        return
    }

    applyAdaptiveLuminanceGlass(
        luminance = luminance,
        lowLuminanceBlurRadius = lowLuminanceBlurRadius,
        neutralBlurRadius = neutralBlurRadius,
        highLuminanceBlurRadius = highLuminanceBlurRadius,
        saturation = saturation
    )
}

internal fun BackdropEffectScope.applyAdaptiveLuminanceGlass(
    luminance: Float,
    lowLuminanceBlurRadius: Float,
    neutralBlurRadius: Float,
    highLuminanceBlurRadius: Float,
    saturation: Float,
    brightnessOffset: Float = 0f,
    neutralContrast: Float = 1f
) {
    val adjustedLuminance = (luminance * 2f - 1f).let { sign(it) * it * it }

    colorControls(
        brightness =
            if (adjustedLuminance > 0f) {
                lerp(NeutralLuminanceBrightness, HighLuminanceBrightness, adjustedLuminance)
            } else {
                lerp(NeutralLuminanceBrightness, LowLuminanceBrightness, -adjustedLuminance)
            } + brightnessOffset,
        contrast =
            if (adjustedLuminance > 0f) {
                lerp(neutralContrast, HighLuminanceContrast, adjustedLuminance)
            } else {
                neutralContrast
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
