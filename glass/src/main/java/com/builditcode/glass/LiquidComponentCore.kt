package com.builditcode.glass

import androidx.compose.animation.core.Spring
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Stable
data class LiquidComponentColors(
    val content: Color = Color.White,
    val secondaryContent: Color = Color.White.copy(alpha = 0.7f),
    val tint: Color = Color.White.copy(alpha = 0.08f),
    val border: Color = Color(131, 131, 131, 155),
    val glow: Color = Color(3, 169, 244, 155)
)

@Composable
internal fun LiquidSurface(
    modifier: Modifier,
    layerName: String?,
    shape: Shape,
    filter: BackdropFilter.Glass,
    colors: LiquidComponentColors,
    visuals: LiquidInteractionVisuals,
    enabled: Boolean,
    showBorder: Boolean,
    borderRotationDegrees: Float,
    gapSize: Float = 0.08f,
    softness: Float = 0.06f,
    content: @Composable BoxScope.() -> Unit
) {
    val liquidShape = LiquidMorphShape(
        baseShape = shape,
        progress = visuals.shapeMorph
    )
    var surfaceModifier = modifier.graphicsLayer {
        scaleX = visuals.scale
        scaleY = visuals.scale
        alpha = if (enabled) 1f else 0.48f
    }

    surfaceModifier = if (layerName != null) {
        surfaceModifier.layeredBackdropCapture(
            layerName = layerName,
            shape = liquidShape,
            filter = filter
        )
    } else {
        surfaceModifier.clip(liquidShape)
    }

    Box(
        modifier = surfaceModifier
            .then(
                if(showBorder) {
                    Modifier.glassBorder(
                        shape = liquidShape,
                        borderColor = colors.border.copy(alpha = 0.7f + visuals.pressProgress * 0.24f),
                        borderWidth = 1.dp,
                        gapSize = gapSize,
                        softness = softness,
                        rotationDegrees = borderRotationDegrees
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val surfaceFill = Modifier
            .matchParentSize()
            .clip(liquidShape)
        Box(
            if (layerName == null) {
                surfaceFill.background(colors.tint)
            } else {
                surfaceFill.background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f + visuals.brightness),
                            Color.White.copy(alpha = 0.04f + visuals.brightness * 0.42f)
                        )
                    )
                )
            }
        )
        content()
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
    showBorder: Boolean,
    blurRadiusIntensity: Float = 4f
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
        modifier = handleModifier.then(
            if(showBorder) {
                Modifier
                    .glassBorder(
                        shape = shape,
                        borderColor = colors.border.copy(alpha = 0.72f),
                        borderWidth = 1.dp,
                        gapSize = 0.04f,
                        softness = 0.04f,
                        rotationDegrees = borderRotationDegrees
                    )
            } else Modifier
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
                            colors.content.copy(alpha = 0.74f),
                            colors.content.copy(alpha = 0.46f)
                        )
                    )
                )
            }
        )
    }
}

@Composable
internal fun rememberLiquidInteractionVisuals(active: Boolean): LiquidInteractionVisuals {
    val pressProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = liquidSpring(),
        label = "liquid-press-progress"
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1.035f else 1f,
        animationSpec = liquidSpring(),
        label = "liquid-scale"
    )
    val brightness by animateFloatAsState(
        targetValue = if (active) 0.12f else 0f,
        animationSpec = liquidSpring(),
        label = "liquid-brightness"
    )
    val shapeMorph by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = liquidSpring(),
        label = "liquid-shape-morph"
    )
    return LiquidInteractionVisuals(
        pressProgress = pressProgress,
        scale = scale,
        brightness = brightness,
        shapeMorph = shapeMorph
    )
}

data class LiquidInteractionVisuals(
    val pressProgress: Float,
    val scale: Float,
    val brightness: Float,
    val shapeMorph: Float
)

internal class LiquidMorphShape(
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
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
)

internal fun <T> liquidDpSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
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
