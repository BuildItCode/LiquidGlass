package com.builditcode.glass

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
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
 * @param onValueChangeFinished Called after a completed drag, tap, or keyboard/accessibility edit.
 * Canceled gestures do not invoke this callback.
 * @param colors Colors used for track, handle tint, border, and glow.
 * @param blurRadiusIntensity Blur amount used by the glass handle when [layerName] is set.
 * @param borderRotationDegrees Additional rotation for the glass handle border highlight.
 * @param height Total touch and layout height for the slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val state = remember(steps, valueRange) { SliderState(value, steps, valueRange = valueRange) }
    // Keep collectors attached while disabling or replacing the range so they receive
    // the terminal Cancel interaction and can clear the thumb's pressed appearance.
    val interactions = remember { LiquidSliderInteractionSource() }
    state.value = value
    state.onValueChange = { if (enabled) onValueChange(it) }
    state.onValueChangeFinished = {
        // Foundation invokes onDragStopped for both completion and cancellation. Inspect
        // its synchronous interaction emission before forwarding Material's callback.
        val canceled = interactions.consumeDragCancellation()
        if (enabled && !canceled) onValueChangeFinished?.invoke()
    }
    val pressed by interactions.collectIsPressedAsState()
    val dragging by interactions.collectIsDraggedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val visuals = rememberLiquidInteractionState(enabled && (pressed || dragging), enabled && focused)
    val thumbShape = RoundedCornerShape(50)

    // Foundation handles horizontal touch slop, RTL, keyboard and accessibility actions.
    // Position follows the value directly; only the thumb's optical press response springs.
    Slider(
        state = state,
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier.widthIn(min = 160.dp).heightIn(min = height.coerceAtLeast(48.dp))
            .graphicsLayer { alpha = if (enabled) 1f else 0.48f }
            .semantics {
                // Resolve the step before testing equality. Material's action accepts
                // disabled edits and fails to snap negative targets before reporting them.
                setProgress { target ->
                    if (!enabled || target.isNaN()) return@setProgress false
                    val resolved = snapLiquidSliderValue(target, valueRange, steps)
                    if (resolved == state.value) return@setProgress false
                    onValueChange(resolved)
                    onValueChangeFinished?.invoke()
                    true
                }
            },
        thumb = {
            LiquidGlassHandle(
                modifier = Modifier.size(42.dp, 28.dp).graphicsLayer {
                    val progress = visuals.press.value.coerceIn(0f, 1f)
                    scaleX = 1f + progress * 0.12f
                    scaleY = 1f + progress * 0.10f
                },
                layerName = layerName,
                shape = thumbShape,
                colors = colors,
                enabled = true,
                visuals = visuals,
                blurRadiusIntensity = blurRadiusIntensity,
                borderRotationDegrees = borderRotationDegrees
            )
        },
        track = { state -> LiquidSliderTrack(state, colors) }
    )
}

private fun snapLiquidSliderValue(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val clamped = value.coerceIn(range)
    val span = range.endInclusive - range.start
    if (steps == 0 || span <= 0f) return clamped
    val intervals = steps + 1
    val fraction = ((clamped - range.start) / span).coerceIn(0f, 1f)
    val step = (fraction * intervals).roundToInt()
    return lerp(range.start, range.endInclusive, step / intervals.toFloat()).coerceIn(range)
}

private class LiquidSliderInteractionSource(
    private val delegate: MutableInteractionSource = MutableInteractionSource()
) : MutableInteractionSource by delegate {
    private var dragCanceled = false

    override suspend fun emit(interaction: Interaction) {
        record(interaction)
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        record(interaction)
        return delegate.tryEmit(interaction)
    }

    private fun record(interaction: Interaction) {
        when (interaction) {
            is DragInteraction.Cancel -> dragCanceled = true
            is DragInteraction.Start, is DragInteraction.Stop -> dragCanceled = false
        }
    }

    fun consumeDragCancellation(): Boolean = dragCanceled.also { dragCanceled = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiquidSliderTrack(state: SliderState, colors: LiquidComponentColors) {
    Box(Modifier.fillMaxWidth().height(8.dp).drawWithCache {
        val radius = CornerRadius(size.height / 2f)
        val stroke = Stroke(1.dp.toPx())
        onDrawBehind {
            val fraction = state.coercedValueAsFraction
            val filledWidth = size.width * fraction
            drawRoundRect(Color.Black.copy(alpha = 0.18f), cornerRadius = radius)
            drawRoundRect(colors.content.copy(alpha = colors.content.alpha * 0.10f), cornerRadius = radius)
            if (filledWidth > 0f) {
                drawRoundRect(
                    colors.glow,
                    topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - filledWidth else 0f, 0f),
                    size = Size(filledWidth, size.height),
                    cornerRadius = radius
                )
            }
            drawRoundRect(colors.border.copy(alpha = colors.border.alpha * 0.55f), cornerRadius = radius, style = stroke)
        }
    })
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
        var value by remember { mutableFloatStateOf(0.62f) }
        LiquidSlider(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.width(320.dp)
        )
    }
}
