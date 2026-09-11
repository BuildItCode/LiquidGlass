package com.builditcode.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Stable
data class LiquidComponentColors(
    val content: Color = Color.White,
    val secondaryContent: Color = Color.White.copy(alpha = 0.7f),
    val tint: Color = Color.White.copy(alpha = 0.08f),
    val border: Color = Color.White.copy(alpha = 0.38f),
    val glow: Color = Color(3, 169, 244, 155)
)

/** Animation values are read in drawing/layer blocks, keeping capture geometry stable. */
@Stable
internal class LiquidInteractionState(val press: State<Float>, val focus: State<Float>)

@Composable
internal fun rememberLiquidInteractionState(pressed: Boolean, focused: Boolean = false): LiquidInteractionState {
    val press = animateFloatAsState(if (pressed) 1f else 0f, liquidSpring(), label = "liquid-press")
    val focus = animateFloatAsState(if (focused) 1f else 0f, liquidSpring(), label = "liquid-focus")
    return remember(press, focus) { LiquidInteractionState(press, focus) }
}

@Composable
internal fun LiquidSurface(
    modifier: Modifier,
    layerName: String?,
    shape: Shape,
    filter: BackdropFilter.Glass,
    colors: LiquidComponentColors,
    visuals: LiquidInteractionState,
    enabled: Boolean,
    showBorder: Boolean,
    borderRotationDegrees: Float,
    gapSize: Float = 0.08f,
    softness: Float = 0.06f,
    content: @Composable BoxScope.() -> Unit
) {
    var surfaceModifier = modifier.graphicsLayer {
        val progress = visuals.press.value.coerceIn(0f, 1f)
        scaleX = 1f - progress * 0.018f
        scaleY = 1f - progress * 0.018f
        alpha = if (enabled) 1f else 0.48f
    }
    surfaceModifier = if (layerName != null) {
        surfaceModifier.layeredBackdropCapture(layerName, shape = shape, filter = filter)
    } else surfaceModifier.clip(shape)

    if (showBorder) {
        surfaceModifier = surfaceModifier.glassBorder(
            shape = shape, borderColor = colors.border, borderWidth = 1.dp,
            gapSize = gapSize, softness = softness, rotationDegrees = borderRotationDegrees
        )
    }
    CompositionLocalProvider(LocalContentColor provides colors.content) {
        Box(
            modifier = surfaceModifier.drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val sheen = Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.015f)
                ))
                val focusStroke = Stroke(2.dp.toPx())
                onDrawWithContent {
                    if (layerName == null) drawOutline(outline, colors.tint)
                    drawOutline(outline, sheen)
                    drawOutline(outline, Color.White, alpha = visuals.press.value.coerceIn(0f, 1f) * 0.08f)
                    drawContent()
                    drawOutline(outline, colors.glow, alpha = visuals.focus.value.coerceIn(0f, 1f), style = focusStroke)
                }
            },
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

fun Modifier.liquidAsymmetricPress(visuals: LiquidInteractionVisuals): Modifier =
    graphicsLayer {
        scaleX = 1f + visuals.pressProgress * 0.075f
        scaleY = 1f - visuals.pressProgress * 0.018f
        translationX = size.width * 0.012f * visuals.pressProgress
    }

@Composable
internal fun LiquidGlassHandle(
    modifier: Modifier,
    layerName: String?,
    shape: Shape,
    colors: LiquidComponentColors,
    enabled: Boolean,
    borderRotationDegrees: Float,
    blurRadiusIntensity: Float = 4f,
    visuals: LiquidInteractionState? = null
) {
    val filter = remember(shape, colors.tint, blurRadiusIntensity) {
        BackdropFilter.Glass(
            blurRadiusIntensity = blurRadiusIntensity,
            tint = colors.tint,
            shape = shape
        )
    }

    var handleModifier = modifier.graphicsLayer {
        alpha = if (enabled) 1f else 0.48f
    }
    handleModifier = if (layerName != null) {
        handleModifier.layeredBackdropCapture(
            layerName = layerName,
            shape = shape,
            filter = filter
        )
    } else {
        handleModifier.clip(shape)
    }

    Box(
        modifier = handleModifier.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val stroke = Stroke(2.dp.toPx())
            onDrawWithContent {
                drawContent()
                val focus = visuals?.focus?.value ?: 0f
                if (focus > 0f) drawOutline(outline, colors.glow, alpha = focus.coerceIn(0f, 1f), style = stroke)
            }
        }.glassBorder(
            shape = shape,
            borderColor = colors.border,
            borderWidth = 1.dp,
            gapSize = 0.04f,
            softness = 0.04f,
            rotationDegrees = borderRotationDegrees
        )
    ) {
        val handleFill = Modifier
            .fillMaxSize()
            .clip(shape)
        Box(
            if (layerName == null) {
                handleFill.background(colors.content)
            } else {
                handleFill.background(
                    Brush.verticalGradient(
                        listOf(
                            colors.content.copy(alpha = colors.content.alpha * 0.86f),
                            colors.content.copy(alpha = colors.content.alpha * 0.62f)
                        )
                    )
                )
            }
        )
    }
}


data class LiquidInteractionVisuals(
    val pressProgress: Float,
    val scale: Float,
    val brightness: Float,
    val shapeMorph: Float
)

internal data class LiquidMorphShape(
    private val baseShape: Shape,
    private val progress: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val outline = baseShape.createOutline(size, layoutDirection, density)
        if (progress <= 0.001f || outline !is Outline.Rounded) return outline

        val rr = outline.roundRect
        val maxDelta = size.minDimension * 0.12f * progress.coerceIn(0f, 1f)
        return Outline.Rounded(
            RoundRect(
                left = rr.left,
                top = rr.top,
                right = rr.right,
                bottom = rr.bottom,
                topLeftCornerRadius = rr.topLeftCornerRadius.liquidMorph(maxDelta * 0.40f),
                topRightCornerRadius = rr.topRightCornerRadius.liquidMorph(maxDelta),
                bottomRightCornerRadius = rr.bottomRightCornerRadius.liquidMorph(maxDelta * 0.45f),
                bottomLeftCornerRadius = rr.bottomLeftCornerRadius.liquidMorph(maxDelta * 0.85f)
            )
        )
    }
}

private fun CornerRadius.liquidMorph(delta: Float): CornerRadius =
    CornerRadius(
        x = (x + delta).coerceAtLeast(0f),
        y = (y - delta * 0.35f).coerceAtLeast(0f)
    )

internal fun liquidSpring() = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = 650f
)

@Composable
internal fun LiquidSearchGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(
            color = color,
            radius = size.minDimension * 0.32f,
            center = Offset(size.width * 0.42f, size.height * 0.42f),
            style = stroke
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.86f, size.height * 0.86f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

internal fun Float.toSliderFraction(range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((this - range.start) / span).coerceIn(0f, 1f)
}

internal fun defaultLiquidShape(radius: Int) = RoundedCornerShape(radius.dp)
