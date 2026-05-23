package com.builditcode.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A compact liquid glass switch.
 *
 * The track uses the library colors and rotating rim; the thumb is a small glass handle
 * that can optionally capture a backdrop layer through [layerName].
 *
 * @param checked Current checked state.
 * @param onCheckedChange Called with the next checked state when toggled.
 * @param modifier Modifier applied to the switch track.
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
    val thumbWidth by animateDpAsState(
        targetValue = if (pressed) 40.dp else 34.dp,
        animationSpec = liquidDpSpring(),
        label = "liquid-toggle-thumb-width"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 64.dp - thumbWidth - 2.dp else 2.dp,
        animationSpec = liquidDpSpring(),
        label = "liquid-toggle-thumb-offset"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            colors.glow.copy(alpha = 0.32f)
        } else {
            colors.tint.copy(alpha = 0.16f)
        },
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 420f
        ),
        label = "liquid-toggle-track-color"
    )
    val visuals = rememberLiquidInteractionVisuals(active = pressed)
    val trackShape = RoundedCornerShape(17.dp)
    val thumbShape = LiquidMorphShape(
        baseShape = RoundedCornerShape(15.dp),
        progress = visuals.shapeMorph
    )

    Box(
        modifier = modifier
            .size(width = 64.dp, height = 32.dp)
            .alpha(if (enabled) 1f else 0.48f)
            .clip(trackShape)
            .background(trackColor)
            .glassBorder(
                shape = trackShape,
                borderColor = colors.border.copy(alpha = 0.38f),
                borderWidth = 1.dp,
                gapSize = 0.05f,
                softness = 0.05f,
                rotationDegrees = borderRotationDegrees
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        LiquidGlassHandle(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(width = thumbWidth, height = 28.dp),
            layerName = layerName,
            shape = thumbShape,
            colors = colors,
            enabled = enabled,
            borderRotationDegrees = borderRotationDegrees,
            blurRadiusIntensity = blurRadiusIntensity,
        )
    }
}

@Preview(
    name = "LiquidToggle",
    group = "Liquid Components",
    showBackground = true,
    backgroundColor = 0xFF101114
)
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
