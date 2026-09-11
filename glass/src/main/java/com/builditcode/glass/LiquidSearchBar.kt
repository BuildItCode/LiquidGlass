package com.builditcode.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * @param onSearch Optional action for the keyboard search key.
 * @param clearButtonContentDescription Accessible, localizable label for clearing the text.
 */
@Composable
fun LiquidSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    layerName: String? = null,
    placeholder: String = "Search",
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: LiquidComponentColors = LiquidComponentColors(),
    blurRadiusIntensity: Float = 5f,
    borderRotationDegrees: Float = 0f,
    showBorder: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onSearch: ((String) -> Unit)? = null,
    clearButtonContentDescription: String = "Clear search"
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val visuals = rememberLiquidInteractionState(pressed && enabled, focused && enabled)
    val keyboard = LocalSoftwareKeyboardController.current
    val textLineHeight = with(LocalDensity.current) { 24.sp.toDp() }
    val filter = remember(shape, colors.tint, blurRadiusIntensity) {
        BackdropFilter.Glass(
            blurRadiusIntensity = blurRadiusIntensity, tint = colors.tint, shape = shape,
            refraction = 0.3f, edge = 0.24f, dispersion = 0.24f
        )
    }
    LiquidSurface(
        modifier = modifier.heightIn(min = 52.dp).widthIn(min = 220.dp),
        layerName = layerName,
        shape = shape,
        filter = filter,
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = enabled,
            interactionSource = interactionSource,
            singleLine = true,
            textStyle = TextStyle(color = colors.content, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
            cursorBrush = SolidColor(colors.content),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                // A queued IME/accessibility action can arrive after editing is disabled.
                if (enabled) {
                    onSearch?.invoke(value)
                    keyboard?.hide()
                }
            }),
            decorationBox = { innerTextField ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiquidSearchGlyph(if (focused && enabled) colors.content else colors.secondaryContent)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f).heightIn(min = textLineHeight + 24.dp).padding(vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // Keep placeholder metrics in the layout even while editing: the
                        // single-line editor and Text can have different font padding.
                        Text(
                            placeholder,
                            modifier = if (value.isEmpty()) Modifier else Modifier.clearAndSetSemantics { },
                            color = if (value.isEmpty()) colors.secondaryContent else Color.Transparent,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        innerTextField()
                    }
                    // Reserve the clear action's width so typing never shifts the text.
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        LiquidSearchClearButton(value.isNotEmpty(), enabled, colors, clearButtonContentDescription) { onValueChange("") }
                    }
                }
            }
        )
    }
}

@Composable
private fun LiquidSearchClearButton(visible: Boolean, enabled: Boolean, colors: LiquidComponentColors, label: String, onClear: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(120)), exit = fadeOut(tween(90))) {
        IconButton(onClick = onClear, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
            Canvas(Modifier.size(16.dp)) {
                val inset = 3.dp.toPx()
                drawLine(colors.secondaryContent, Offset(inset, inset), Offset(size.width - inset, size.height - inset), 2.dp.toPx(), StrokeCap.Round)
                drawLine(colors.secondaryContent, Offset(size.width - inset, inset), Offset(inset, size.height - inset), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Preview(
    name = "LiquidSearchBar",
    group = "Liquid Components",
    showBackground = true,
    backgroundColor = 0xFF101114
)
@Composable
fun LiquidSearchBarPreview() {
    LiquidPreviewScene {
        var search by remember { mutableStateOf("") }
        LiquidSearchBar(
            value = search,
            onValueChange = { search = it },
            placeholder = "Search apps",
            modifier = Modifier.width(320.dp).height(56.dp)
        )
    }
}
