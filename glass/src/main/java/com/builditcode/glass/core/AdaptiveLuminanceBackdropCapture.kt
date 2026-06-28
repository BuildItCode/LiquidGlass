package com.builditcode.glass.core

import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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

private const val FIRST_SAMPLE_RETRY_MILLIS = 100L
private const val FIRST_SAMPLE_MAX_RETRIES = 10
private const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 1_000L
private const val DEFAULT_LUMINANCE_ANIMATION_MILLIS = 1_000

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

private class AdaptiveLuminanceSampleGate {
    private var requestedGeneration = 1
    private var recordedGeneration = 0

    val shouldRecord: Boolean
        get() = recordedGeneration < requestedGeneration

    fun requestSample() {
        requestedGeneration++
    }

    fun markRecorded() {
        recordedGeneration = requestedGeneration
    }
}

@Composable
fun rememberAdaptiveLuminanceState(
    initialLuminance: Float = 0.5f,
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
    sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL_MILLIS,
    contentColorAnimationSpec: AnimationSpec<Color> = tween(DEFAULT_LUMINANCE_ANIMATION_MILLIS),
    luminanceAnimationSpec: AnimationSpec<Float> = tween(DEFAULT_LUMINANCE_ANIMATION_MILLIS),
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val luminanceLayer = rememberGraphicsLayer()
    val normalizedSampleSize = sampleSize.coerceAtLeast(1)
    val sampleGate = remember { AdaptiveLuminanceSampleGate() }

    LaunchedEffect(
        luminanceLayer,
        sampleGate,
        state,
        normalizedSampleSize,
        sampleIntervalMillis,
        contentColorAnimationSpec,
        luminanceAnimationSpec
    ) {
        val buffer = IntArray(normalizedSampleSize * normalizedSampleSize)
        val luminanceAnimation = FloatAnimatable(state.luminance)
        var hasValidSample = false
        var fastRetryCount = 0

        while (isActive) {
            sampleGate.requestSample()
            withFrameNanos { }

            val luminance = luminanceLayer.readAverageLuminance(
                sampleSize = normalizedSampleSize,
                buffer = buffer
            )

            if (luminance != null) {
                hasValidSample = true
                launch {
                    state.contentColor.animateTo(
                        if (luminance > 0.5f) Color.Black else Color.White,
                        contentColorAnimationSpec
                    )
                }
                launch {
                    luminanceAnimation.animateTo(luminance, luminanceAnimationSpec) {
                        state.luminance = value
                    }
                }
            } else if (!hasValidSample && fastRetryCount < FIRST_SAMPLE_MAX_RETRIES) {
                fastRetryCount++
            }

            val nextDelayMillis =
                if (!hasValidSample && fastRetryCount < FIRST_SAMPLE_MAX_RETRIES) {
                    FIRST_SAMPLE_RETRY_MILLIS
                } else {
                    sampleIntervalMillis
                }
            delay(nextDelayMillis.coerceAtLeast(FIRST_SAMPLE_RETRY_MILLIS).milliseconds)
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
            if (sampleGate.shouldRecord) {
                luminanceLayer.record { drawBackdrop() }
                sampleGate.markRecorded()
            }
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

    var alphaTotal = 0.0
    val luminanceTotal = buffer.sumOf { argb ->
        val a = (argb ushr 24 and 0xFF) / 255.0
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        alphaTotal += a
        (0.2126 * r + 0.7152 * g + 0.0722 * b) * a
    }
    if (alphaTotal <= 0.0) return null

    return (luminanceTotal / alphaTotal).toFloat()
}

private fun ImageBitmap.scale(width: Int, height: Int): ImageBitmap =
    Bitmap.createScaledBitmap(asAndroidBitmap(), width, height, false)
        .copy(Bitmap.Config.ARGB_8888, false)
        .asImageBitmap()
