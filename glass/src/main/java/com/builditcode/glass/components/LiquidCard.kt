package com.builditcode.glass.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.builditcode.glass.core.LocalBackdropLayerName

/**
 * A liquid glass content container.
 *
 * The card can be passive or clickable. When [onClick] is provided it gets the same spring
 * press, shape morph, brightness, and accessibility role behavior as the other liquid
 * controls. Pass [layerName] to sample a live backdrop layer behind the card.
 *
 * @param modifier Modifier applied to the outer card surface.
 * @param layerName Optional backdrop source layer to sample.
 * @param enabled Whether click and interaction feedback are enabled.
 * @param onClick Optional click handler. When null, the card is non-interactive.
 * @param shape Shape used for clipping, glass capture, and border drawing.
 * @param colors Colors used for content, tint, border, and glow.
 * @param contentPadding Padding applied inside the card.
 * @param blurRadiusIntensity Blur amount used when [layerName] enables backdrop capture.
 * @param borderRotationDegrees Additional rotation for the border highlight.
 * @param interactionSource Source used to observe pressed state when clickable.
 * @param adaptiveLuminance Whether the glass adapts blur and brightness to sampled luminance.
 * @param content Card content.
 */
@Composable
fun LiquidCard(
    modifier: Modifier = Modifier,
    layerName: String? = LocalBackdropLayerName.current,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    colors: LiquidComponentColors = LiquidComponentColors(),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    blurRadiusIntensity: Float = 5f,
    borderRotationDegrees: Float = 0f,
    showBorder: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    adaptiveLuminance: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val interactive = enabled && onClick != null
    val visuals = rememberLiquidInteractionVisuals(active = pressed && interactive)

    var cardModifier = modifier
        .liquidAsymmetricPress(visuals)
        .clip(shape)

    if (onClick != null) {
        cardModifier = cardModifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick
        )
    }

    LiquidSurface(
        modifier = cardModifier,
        layerName = layerName,
        shape = shape,
        adaptiveLuminance = adaptiveLuminance,
        effects = { luminance ->
            liquidGlassEffects(
                blurRadiusIntensity = blurRadiusIntensity,
                brightness = visuals.brightness,
                adaptiveLuminance = adaptiveLuminance,
                luminance = luminance
            )
        },
        colors = colors,
        visuals = visuals,
        enabled = enabled,
        showBorder = showBorder,
        borderRotationDegrees = borderRotationDegrees
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}
