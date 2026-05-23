package com.builditcode.glass

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A liquid glass slider with a slim track and animated glass handle.
 *
 * The track is drawn locally for low overhead, while the handle can optionally sample a live
 * backdrop layer by passing [layerName]. Dragging stretches the handle and reports values
 * in [valueRange], snapping to [steps] when provided.
 *
 * @param value Current slider value.
 * @param onValueChange Called as the user drags or taps the slider.
 * @param modifier Modifier applied to the slider bounds.
 * @param layerName Optional backdrop source layer sampled by the handle.
 * @param valueRange Allowed value range.
 * @param enabled Whether dragging and interaction feedback are enabled.
 * @param steps Number of discrete steps between the ends of [valueRange].
 * @param onValueChangeFinished Called when a drag gesture ends.
 * @param colors Colors used for track, handle tint, border, and glow.
 * @param blurRadiusIntensity Blur amount used by the glass handle when [layerName] is set.
 * @param borderRotationDegrees Additional rotation for the track and handle border highlights.
 * @param height Total touch and layout height for the slider.
 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    layerName: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: LiquidComponentColors = LiquidComponentColors(),
    blurRadiusIntensity: Float = 4f,
    borderRotationDegrees: Float = 0f,
    height: Dp = 52.dp
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var lastReportedValue by remember(value) { mutableFloatStateOf(value) }
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val fraction = valueRange.fractionFor(coercedValue)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = liquidSpring(),
        label = "liquid-slider-fraction"
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (dragging) 58.dp else 42.dp,
        animationSpec = liquidDpSpring(),
        label = "liquid-slider-thumb-width"
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (dragging) 32.dp else 28.dp,
        animationSpec = liquidDpSpring(),
        label = "liquid-slider-thumb-height"
    )
    val visuals = rememberLiquidInteractionVisuals(active = dragging)
    val thumbShape = LiquidMorphShape(
        baseShape = RoundedCornerShape(16.dp),
        progress = visuals.shapeMorph
    )
    val thumbWidthPx = with(LocalDensity.current) { thumbWidth.roundToPx() }

    Box(
        modifier = modifier
            .widthIn(min = 160.dp)
            .height(height)
            .alpha(if (enabled) 1f else 0.48f)
            .progressSemantics(coercedValue, valueRange, steps)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, valueRange.start, valueRange.endInclusive, steps, widthPx) {
                if (!enabled || widthPx == 0) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true

                    fun updateValueFromX(x: Float) {
                        val nextValue = valueRange.valueFor(
                            x = x,
                            widthPx = widthPx,
                            thumbSizePx = thumbWidth.toPx(),
                            steps = steps
                        )
                        if (nextValue != lastReportedValue) {
                            lastReportedValue = nextValue
                            onValueChange(nextValue)
                        }
                    }

                    updateValueFromX(down.position.x)
                    drag(down.id) { change ->
                        updateValueFromX(change.position.x)
                        change.consume()
                    }

                    onValueChangeFinished?.invoke()
                    dragging = false
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        LiquidSliderTrack(
            fraction = animatedFraction,
            colors = colors,
            borderRotationDegrees = borderRotationDegrees
        )

        LiquidGlassHandle(
            modifier = Modifier
                .sliderThumbOffset(
                    widthPx = widthPx,
                    fraction = animatedFraction,
                    thumbSizePx = thumbWidthPx
                )
                .size(width = thumbWidth, height = thumbHeight),
            layerName = layerName,
            shape = thumbShape,
            colors = colors,
            enabled = enabled,
            blurRadiusIntensity = blurRadiusIntensity,
            borderRotationDegrees = borderRotationDegrees
        )
    }
}

@Composable
private fun LiquidSliderTrack(
    fraction: Float,
    colors: LiquidComponentColors,
    borderRotationDegrees: Float
) {
    val shape = RoundedCornerShape(7.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(shape)
            .background(colors.tint.copy(alpha = 0.1f))
            .glassBorder(
                shape = shape,
                borderColor = colors.border.copy(alpha = 0.36f),
                borderWidth = 1.dp,
                rotationDegrees = borderRotationDegrees
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(colors.glow.copy(alpha = 0.42f))
        )
    }
}

private fun Modifier.sliderThumbOffset(
    widthPx: Int,
    fraction: Float,
    thumbSizePx: Int
): Modifier = offset {
    val travelPx = (widthPx - thumbSizePx).coerceAtLeast(0)
    IntOffset(
        x = (travelPx * fraction.coerceIn(0f, 1f)).roundToInt(),
        y = 0
    )
}

private fun ClosedFloatingPointRange<Float>.fractionFor(value: Float): Float {
    val span = endInclusive - start
    if (span == 0f) return 0f
    return ((value - start) / span).coerceIn(0f, 1f)
}

private fun ClosedFloatingPointRange<Float>.valueFor(
    x: Float,
    widthPx: Int,
    thumbSizePx: Float,
    steps: Int
): Float {
    val travelPx = (widthPx - thumbSizePx).coerceAtLeast(1f)
    val rawFraction = ((x - thumbSizePx / 2f) / travelPx).coerceIn(0f, 1f)
    val fraction = if (steps > 0) {
        val intervals = steps + 1
        (rawFraction * intervals).roundToInt() / intervals.toFloat()
    } else {
        rawFraction
    }
    return start + (endInclusive - start) * fraction
}

@Preview(
    name = "LiquidSlider",
    group = "Liquid Components",
    showBackground = true,
    backgroundColor = 0xFF101114
)
@Composable
fun LiquidSliderPreview() {
    LiquidPreviewScene {
        var value by remember { mutableStateOf(0.62f) }
        LiquidSlider(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.width(320.dp)
        )
    }
}
