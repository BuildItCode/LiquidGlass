package com.builditcode.glass.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
import com.builditcode.glass.core.BackdropEffectScope
import com.builditcode.glass.core.effects.applyAdaptiveLuminanceGlass
import com.builditcode.glass.core.effects.blur
import com.builditcode.glass.core.effects.colorControls
import com.builditcode.glass.core.effects.lens
import com.builditcode.glass.core.layeredAdaptiveLuminanceBackdropCapture
import com.builditcode.glass.core.layeredBackdropCapture
import com.builditcode.glass.core.rememberAdaptiveLuminanceState

private const val LiquidGlassNeutralBrightness = 0.1f
private const val LiquidGlassNeutralContrast = 1.05f
private const val LiquidGlassSaturation = 1.3f
private const val LiquidGlassPressBrightnessMultiplier = 0.18f
private const val LiquidGlassLowLuminanceBlurMultiplier = 0.25f
private const val LiquidGlassHighLuminanceBlurMultiplier = 1.5f
private const val LiquidGlassRefractionHeightFraction = 0.12f
private const val LiquidGlassRefractionAmountFraction = 0.30f

@Stable
data class LiquidComponentColors(
    val content: Color = Color.White,
    val secondaryContent: Color = Color.White.copy(alpha = 0.7f),
    val tint: Color = Color.White.copy(alpha = 0.08f),
    val border: Color = Color(131, 131, 131, 155),
    val glow: Color = Color(3, 169, 244, 155)
)

object LiquidComponentDefaults {
    @Composable
    fun accentColor(): Color =
        if (!isSystemInDarkTheme()) Color(0xFF0088FF)
        else Color(0xFF0091FF)

    @Composable
    fun bottomTabsContainerColor(): Color =
        if (!isSystemInDarkTheme()) Color(0xFFFAFAFA).copy(alpha = 0.1f)
        else Color(0xFF121212).copy(alpha = 0.1f)

    @Composable
    fun bottomTabsSelectionColor(): Color =
        if (!isSystemInDarkTheme()) Color.Black.copy(alpha = 0.1f)
        else Color.White.copy(alpha = 0.1f)

    @Composable
    fun controlTrackColor(): Color =
        if (!isSystemInDarkTheme()) Color(0xFF787878).copy(alpha = 0.2f)
        else Color(0xFF787880).copy(alpha = 0.36f)
}

@Composable
internal fun LiquidSurface(
    modifier: Modifier,
    layerName: String?,
    shape: Shape,
    adaptiveLuminance: Boolean,
    effects: BackdropEffectScope.(luminance: Float?) -> Unit,
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
        if (adaptiveLuminance) {
            val adaptiveLuminanceState = rememberAdaptiveLuminanceState()
            surfaceModifier.layeredAdaptiveLuminanceBackdropCapture(
                layerName = layerName,
                state = adaptiveLuminanceState,
                shape = { liquidShape },
                effects = {
                    effects(if (hasLuminanceSample) luminance else null)
                }
            )
        } else {
            surfaceModifier.layeredBackdropCapture(
                layerName = layerName,
                shape = { liquidShape },
                effects = {
                    effects(null)
                }
            )
        }
    } else {
        surfaceModifier.clip(liquidShape)
    }

    Box(
        modifier = surfaceModifier.then(
            if (showBorder) {
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
                            colors.tint.copy(alpha = colors.tint.alpha + visuals.brightness),
                            colors.tint.copy(alpha = colors.tint.alpha + visuals.brightness * 0.42f)
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
    blurRadiusIntensity: Float = 4f,
    adaptiveLuminance: Boolean = true
) {
    var handleModifier = modifier.graphicsLayer {
        alpha = if (enabled) 1f else 0.48f
    }
    handleModifier = if (layerName != null) {
        if (adaptiveLuminance) {
            val adaptiveLuminanceState = rememberAdaptiveLuminanceState()
            handleModifier.layeredAdaptiveLuminanceBackdropCapture(
                layerName = layerName,
                state = adaptiveLuminanceState,
                shape = { shape },
                effects = {
                    liquidGlassEffects(
                        blurRadiusIntensity = blurRadiusIntensity,
                        luminance = if (hasLuminanceSample) luminance else null
                    )
                }
            )
        } else {
            handleModifier.layeredBackdropCapture(
                layerName = layerName,
                shape = { shape },
                effects = {
                    liquidGlassEffects(
                        blurRadiusIntensity = blurRadiusIntensity,
                        adaptiveLuminance = false
                    )
                }
            )
        }
    } else {
        handleModifier.clip(shape)
    }

    Box(
        modifier = handleModifier.glassBorder(
            shape = shape,
            borderColor = colors.border.copy(alpha = 0.72f),
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
                            colors.content.copy(alpha = 0.74f),
                            colors.content.copy(alpha = 0.46f)
                        )
                    )
                )
            }
        )
    }
}

internal fun BackdropEffectScope.liquidGlassEffects(
    blurRadiusIntensity: Float,
    brightness: Float = 0f,
    adaptiveLuminance: Boolean = true,
    luminance: Float? = null
) {
    val neutralBlurRadius = blurRadiusIntensity.dp.toPx()
    val minDimension = size.minDimension
    val pressBrightness = brightness * LiquidGlassPressBrightnessMultiplier

    if (adaptiveLuminance && luminance != null) {
        applyAdaptiveLuminanceGlass(
            luminance = luminance,
            lowLuminanceBlurRadius = neutralBlurRadius * LiquidGlassLowLuminanceBlurMultiplier,
            neutralBlurRadius = neutralBlurRadius,
            highLuminanceBlurRadius = neutralBlurRadius * LiquidGlassHighLuminanceBlurMultiplier,
            saturation = LiquidGlassSaturation,
            brightnessOffset = pressBrightness,
            neutralContrast = LiquidGlassNeutralContrast
        )
    } else {
        colorControls(
            brightness = LiquidGlassNeutralBrightness + pressBrightness,
            contrast = LiquidGlassNeutralContrast,
            saturation = LiquidGlassSaturation
        )
        blur(neutralBlurRadius)
    }
    if (minDimension > 0f) {
        lens(
            refractionHeight = minDimension * LiquidGlassRefractionHeightFraction,
            refractionAmount = minDimension * LiquidGlassRefractionAmountFraction,
            depthEffect = true
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
    Canvas(Modifier.size(16.dp)) {
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
