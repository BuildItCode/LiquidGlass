package com.builditcode.glass.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.builditcode.glass.core.LocalBackdropLayerName

/**
 * A single-line liquid glass search field.
 *
 * By default the control draws its own glass surface without sampling content behind it.
 * Pass [layerName] to opt into [layeredBackdropCapture] for the matching source layer.
 * Use [borderRotationDegrees], typically from [rememberGlassBorderGyroscopeRotation], to
 * rotate the rim highlight with device motion.
 *
 * @param value Current text value.
 * @param onValueChange Called whenever the user edits the text.
 * @param modifier Modifier applied to the outer search surface.
 * @param layerName Optional backdrop source layer to sample.
 * @param placeholder Text shown when [value] is empty.
 * @param enabled Whether text input and interaction feedback are enabled.
 * @param shape Shape used for clipping, glass capture, and border drawing.
 * @param colors Colors used for text, tint, border, and glow.
 * @param blurRadiusIntensity Blur amount used when [layerName] enables backdrop capture.
 * @param borderRotationDegrees Additional rotation for the border highlight.
 * @param interactionSource Interaction source passed to the inner text field.
 */
@Composable
fun LiquidSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    layerName: String? = LocalBackdropLayerName.current,
    placeholder: String = "Search",
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: LiquidComponentColors = LiquidComponentColors(),
    blurRadiusIntensity: Float = 5f,
    borderRotationDegrees: Float = 0f,
    showBorder: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    var pressed by remember { mutableStateOf(false) }
    val visuals = rememberLiquidInteractionVisuals(active = pressed)
    val searchHeight by animateDpAsState(
        targetValue = if (pressed) 54.dp else 52.dp,
        animationSpec = liquidDpSpring(),
        label = "liquid-search-height"
    )

    LiquidSurface(
        modifier = modifier
            .height(searchHeight)
            .liquidAsymmetricPress(visuals)
            .widthIn(min = 220.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        layerName = layerName,
        shape = shape,
        effects = {
            liquidGlassEffects(
                blurRadiusIntensity = blurRadiusIntensity,
                brightness = visuals.brightness
            )
        },
        colors = colors,
        visuals = visuals,
        enabled = enabled,
        borderRotationDegrees = borderRotationDegrees,
        showBorder = showBorder,
        gapSize = 0.04f
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 18.dp),
            enabled = enabled,
            interactionSource = interactionSource,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.content,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            ),
            cursorBrush = Brush.verticalGradient(
                listOf(colors.content, colors.content.copy(alpha = 0.6f))
            ),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    LiquidSearchGlyph(colors.secondaryContent)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = colors.secondaryContent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        innerTextField()
                    }
                }
            }
        )
    }
}
