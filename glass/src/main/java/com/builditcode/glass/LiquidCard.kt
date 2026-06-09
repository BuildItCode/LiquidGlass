package com.builditcode.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * @param content Card content.
 */
@Composable
fun LiquidCard(
    modifier: Modifier = Modifier,
    layerName: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    colors: LiquidComponentColors = LiquidComponentColors(),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    blurRadiusIntensity: Float = 5f,
    borderRotationDegrees: Float = 0f,
    showBorder: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.() -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val interactive = enabled && onClick != null
    val visuals = rememberLiquidInteractionVisuals(active = pressed && interactive)
    val filter = remember(shape, colors.tint, blurRadiusIntensity) {
        BackdropFilter.Glass(
            blurRadiusIntensity = blurRadiusIntensity,
            tint = colors.tint,
            shape = shape
        )
    }

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
        filter = filter,
        colors = colors,
        visuals = visuals,
        enabled = enabled,
        showBorder = showBorder,
        borderRotationDegrees = borderRotationDegrees
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Preview(
    name = "LiquidCard",
    group = "Liquid Components",
    showBackground = true,
    backgroundColor = 0xFF101114
)
@Composable
fun LiquidCardPreview() {
    LiquidPreviewScene {
        LiquidCard(
            modifier = Modifier.size(width = 260.dp, height = 150.dp),
            onClick = {}
        ) {
            Column {
                Text(
                    text = "Liquid Card",
                    color = LiquidComponentColors().content,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Glass surface with a living press response.",
                    color = LiquidComponentColors().secondaryContent,
                    fontSize = 14.sp
                )
            }
        }
    }
}
