package com.builditcode.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A compact liquid glass switch.
 *
 * The track uses the library colors and rotating rim; the thumb is a small glass handle
 * that can optionally capture a backdrop layer through [layerName].
 *
 * @param checked Current checked state.
 * @param onCheckedChange Called with the next checked state when toggled.
 * @param modifier Modifier applied to the switch's touch bounds.
 * @param layerName Optional backdrop source layer sampled by the thumb.
 * @param enabled Whether toggling and interaction feedback are enabled.
 * @param colors Colors used for track, thumb tint, border, and glow.
 * @param blurRadiusIntensity Blur amount used by the glass thumb when [layerName] is set.
 * @param borderRotationDegrees Additional rotation for the track and thumb border highlights.
 * @param interactionSource Source used to observe pressed state.
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    layerName: String? = null,
    enabled: Boolean = true,
    colors: LiquidComponentColors = LiquidComponentColors(),
    blurRadiusIntensity: Float = 4f,
    borderRotationDegrees: Float = 0f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val visuals = rememberLiquidInteractionState(pressed && enabled, focused && enabled)
    val position = animateFloatAsState(if (checked) 1f else 0f, liquidSpring(), label = "liquid-toggle-position")
    val shape = RoundedCornerShape(50)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.48f },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(64.dp, 32.dp)
                .glassBorder(shape, colors.border, 1.dp, rotationDegrees = borderRotationDegrees)
                .drawWithCache {
                    val radius = CornerRadius(size.height / 2f)
                    val off = colors.tint.copy(alpha = maxOf(colors.tint.alpha, 0.14f))
                    val on = colors.glow.copy(alpha = colors.glow.alpha * 0.8f)
                    onDrawBehind { drawRoundRect(lerp(off, on, position.value.coerceIn(0f, 1f)), cornerRadius = radius) }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            LiquidGlassHandle(
                modifier = Modifier.layout { measurable, constraints ->
                    val width = constraints.maxWidth
                    val inset = minOf(2.dp.roundToPx(), width / 2)
                    val thumb = measurable.measure(constraints.copy(minWidth = 0, maxWidth = width - inset * 2))
                    val travel = (width - thumb.width - inset * 2).coerceAtLeast(0)
                    layout(width, thumb.height) {
                        // Read animation in placement and use the measured track width,
                        // so tight parent constraints cannot push the thumb out of bounds.
                        thumb.placeRelative(inset + (travel * position.value.coerceIn(0f, 1f)).roundToInt(), 0)
                    }
                }.size(32.dp, 28.dp).graphicsLayer {
                    val fraction = position.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(if (isRtl) 1f - fraction else fraction, 0.5f)
                    scaleX = 1f + visuals.press.value.coerceIn(0f, 1f) * 0.14f
                },
                layerName = layerName,
                shape = shape,
                colors = colors,
                enabled = true,
                visuals = visuals,
                borderRotationDegrees = borderRotationDegrees,
                blurRadiusIntensity = blurRadiusIntensity
            )
        }
    }
}

@PreviewLightDark
@Composable
fun LiquidTogglePreview() {
    LiquidPreviewScene {
        var checked by remember { mutableStateOf(true) }
        LiquidToggle(
            checked = checked,
            onCheckedChange = { checked = it }
        )
    }
}
