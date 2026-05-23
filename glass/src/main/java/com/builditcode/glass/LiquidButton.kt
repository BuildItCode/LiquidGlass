package com.builditcode.glass

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A liquid glass button with text content.
 *
 * This overload is a convenience wrapper around the slot-based [LiquidButton]. Pass
 * [layerName] to sample a live backdrop layer, or leave it `null` for a self-contained
 * glass surface suitable for previews and ordinary layouts.
 *
 * @param text Text shown in the button.
 * @param onClick Called when the button is clicked.
 * @param modifier Modifier applied to the outer button surface.
 * @param layerName Optional backdrop source layer to sample.
 * @param enabled Whether clicks and interaction feedback are enabled.
 * @param shape Shape used for clipping, glass capture, and border drawing.
 * @param colors Colors used for text, tint, border, and glow.
 * @param blurRadiusIntensity Blur amount used when [layerName] enables backdrop capture.
 * @param borderRotationDegrees Additional rotation for the border highlight.
 * @param interactionSource Source used to observe pressed state.
 */
@Composable
fun LiquidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    layerName: String? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(22.dp),
    colors: LiquidComponentColors = LiquidComponentColors(),
    blurRadiusIntensity: Float = 4f,
    borderRotationDegrees: Float = 0f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    LiquidButton(
        onClick = onClick,
        modifier = modifier,
        layerName = layerName,
        enabled = enabled,
        shape = shape,
        colors = colors,
        blurRadiusIntensity = blurRadiusIntensity,
        borderRotationDegrees = borderRotationDegrees,
        interactionSource = interactionSource
    ) {
        Text(
            text = text,
            color = colors.content,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(
    name = "LiquidButton",
    group = "Liquid Components",
    showBackground = true,
    backgroundColor = 0xFF101114
)
@Composable
fun LiquidButtonPreview() {
    LiquidPreviewScene {
        LiquidButton(
            modifier = Modifier.height(56.dp),
            text = "Continue",
            onClick = {}
        )
    }
}

/**
 * A liquid glass button with custom row content.
 *
 * Content is centered in a horizontal row and receives the same spring press, shape morph,
 * brightness, optional backdrop capture, and optional border rotation behavior as the text
 * overload.
 *
 * @param onClick Called when the button is clicked.
 * @param modifier Modifier applied to the outer button surface.
 * @param layerName Optional backdrop source layer to sample.
 * @param enabled Whether clicks and interaction feedback are enabled.
 * @param shape Shape used for clipping, glass capture, and border drawing.
 * @param colors Colors used for content, tint, border, and glow.
 * @param blurRadiusIntensity Blur amount used when [layerName] enables backdrop capture.
 * @param borderRotationDegrees Additional rotation for the border highlight.
 * @param interactionSource Source used to observe pressed state.
 * @param content Custom centered row content.
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    layerName: String? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(22.dp),
    colors: LiquidComponentColors = LiquidComponentColors(),
    blurRadiusIntensity: Float = 4f,
    borderRotationDegrees: Float = 0f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val visuals = rememberLiquidInteractionVisuals(active = pressed)
    val buttonHeight by animateDpAsState(
        targetValue = if (pressed) 49.dp else 48.dp,
        animationSpec = liquidDpSpring(),
        label = "liquid-button-height"
    )
    val filter = remember(shape, colors.tint, blurRadiusIntensity) {
        BackdropFilter.Glass(
            blurRadiusIntensity = blurRadiusIntensity,
            tint = colors.tint,
            shape = shape
        )
    }

    LiquidSurface(
        modifier = modifier
            .height(buttonHeight)
            .liquidAsymmetricPress(visuals)
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        layerName = layerName,
        shape = shape,
        filter = filter,
        colors = colors,
        visuals = visuals,
        enabled = enabled,
        borderRotationDegrees = borderRotationDegrees
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
