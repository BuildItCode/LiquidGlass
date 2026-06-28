package com.builditcode.glass.core

import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.builditcode.glass.core.highlight.Highlight
import com.builditcode.glass.core.shadow.InnerShadow
import com.builditcode.glass.core.shadow.Shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.animation.core.Animatable as FloatAnimatable

@Stable
class AdaptiveLuminanceState internal constructor(
    initialLuminance: Float,
    initialContentColor: Color
) {
    var luminance by mutableFloatStateOf(initialLuminance)
        internal set

    val contentColor = Animatable(initialContentColor)
}

class AdaptiveLuminanceEffectScope internal constructor(
    private val delegate: BackdropEffectScope,
    val adaptiveLuminanceState: AdaptiveLuminanceState
) : BackdropEffectScope by delegate {
    val luminance: Float
        get() = adaptiveLuminanceState.luminance
}

@Composable
fun rememberAdaptiveLuminanceState(
    initialLuminance: Float = if (isSystemInDarkTheme()) 0f else 1f,
    initialContentColor: Color = if (initialLuminance > 0.5f) Color.Black else Color.White
): AdaptiveLuminanceState =
    remember(initialLuminance, initialContentColor) {
        AdaptiveLuminanceState(initialLuminance, initialContentColor)
    }

@Composable
fun Modifier.layeredAdaptiveLuminanceBackdropCapture(
    layerName: String? = LocalBackdropLayerName.current,
    state: AdaptiveLuminanceState = rememberAdaptiveLuminanceState(),
    shape: () -> Shape,
    effects: AdaptiveLuminanceEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = { Highlight.Default },
    shadow: (() -> Shadow?)? = { Shadow.Default },
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    sampleSize: Int = 5,
    sampleIntervalMillis: Long = 1_000L,
    contentColorAnimationSpec: AnimationSpec<Color> = tween(1_000),
    luminanceAnimationSpec: AnimationSpec<Float> = tween(1_000),
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val luminanceLayer = rememberGraphicsLayer()
    val normalizedSampleSize = sampleSize.coerceAtLeast(1)

    LaunchedEffect(
        luminanceLayer,
        state,
        normalizedSampleSize,
        sampleIntervalMillis,
        contentColorAnimationSpec,
        luminanceAnimationSpec
    ) {
        val buffer = IntArray(normalizedSampleSize * normalizedSampleSize)
        val luminanceAnimation = FloatAnimatable(state.luminance)

        while (isActive) {
            val luminance = luminanceLayer.readAverageLuminance(
                sampleSize = normalizedSampleSize,
                buffer = buffer
            )

            if (luminance != null) {
                launch {
                    state.contentColor.animateTo(
                        if (luminance > 0.5f) Color.Black else Color.White,
                        contentColorAnimationSpec
                    )
                }
                luminanceAnimation.animateTo(luminance, luminanceAnimationSpec) {
                    state.luminance = value
                }
            }

            delay(sampleIntervalMillis.coerceAtLeast(16L).milliseconds)
        }
    }

    return layeredBackdropCapture(
        layerName = layerName,
        shape = shape,
        effects = {
            AdaptiveLuminanceEffectScope(this, state).effects()
        },
        highlight = highlight,
        shadow = shadow,
        innerShadow = innerShadow,
        layerBlock = layerBlock,
        onDrawBehind = onDrawBehind,
        onDrawBackdrop = { drawBackdrop ->
            drawBackdrop()
            luminanceLayer.record { drawBackdrop() }
        },
        onDrawSurface = onDrawSurface,
        onDrawFront = onDrawFront
    )
}

private suspend fun GraphicsLayer.readAverageLuminance(
    sampleSize: Int,
    buffer: IntArray
): Float? {
    val source = runCatching { toImageBitmap() }.getOrNull() ?: return null
    if (source.width <= 0 || source.height <= 0) return null

    val sample = source.scale(sampleSize, sampleSize)
    sample.readPixels(buffer)

    return buffer.sumOf { argb ->
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        0.2126 * r + 0.7152 * g + 0.0722 * b
    }.toFloat() / buffer.size
}

private fun ImageBitmap.scale(width: Int, height: Int): ImageBitmap =
    Bitmap.createScaledBitmap(asAndroidBitmap(), width, height, false)
        .copy(Bitmap.Config.ARGB_8888, false)
        .asImageBitmap()
