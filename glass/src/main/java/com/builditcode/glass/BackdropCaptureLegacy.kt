package com.builditcode.glass

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object BackdropCaptureLegacy : BackdropCaptureBackend {
    override val createsFallbackBitmap: Boolean = true
    override val retainsHardwareCaptureLayer: Boolean = false

    override fun usesHardwareLayerForSource(state: BackdropState): Boolean =
        state.shouldUseHardwareSnapshot

    override fun configureCaptureLayer(layer: GraphicsLayer) = Unit

    override fun onHardwareLayerRecorded(
        state: BackdropState,
        layer: GraphicsLayer,
        captureSize: IntSize,
        preferImageSnapshot: Boolean
    ) {
        val session = state.beginHardwareCapture(captureSize)
        state.setHardwareProcessingJob(
            state.captureScope.launch(Dispatchers.Main) {
                var snapshot: ImageBitmap? = null
                try {
                    snapshot = layer.toImageBitmap()
                    snapshot?.softwareCopyFromHardware()?.let { softwareSnapshot ->
                        snapshot?.safeRecycle()
                        snapshot = softwareSnapshot
                    }
                    val captured = snapshot ?: return@launch
                    if (!isActive) {
                        captured.safeRecycle()
                        snapshot = null
                        return@launch
                    }
                    state.applyHardwareImageCapture(captured, session)
                    snapshot = null
                } catch (e: CancellationException) {
                    snapshot?.safeRecycle()
                    throw e
                } catch (e: Exception) {
                    snapshot?.safeRecycle()
                    state.onHardwareCaptureFailed(e, session)
                }
            }
        )
    }

    override fun ContentDrawScope.drawCapture(
        filter: BackdropFilter,
        result: BackdropState.CaptureResult?,
        layer: GraphicsLayer?,
        density: Float,
        cpuBlurCache: CpuBlurCache
    ) {
        layer?.renderEffect = null
        val bitmap = result?.fallbackBitmap
        when (filter) {
            is BackdropFilter.Blur -> drawBlur(filter, result, bitmap, density, cpuBlurCache)
            is BackdropFilter.Glass -> drawGlass(filter, result, bitmap, density, cpuBlurCache)
        }
    }

    private fun ContentDrawScope.drawGlass(
        glass: BackdropFilter.Glass,
        result: BackdropState.CaptureResult?,
        bitmap: ImageBitmap?,
        density: Float,
        cpuBlurCache: CpuBlurCache
    ) {
        val blurPx = glass.blurRadiusIntensity * 2f * density
        if (bitmap != null) {
            val targetSize = result?.drawSize ?: IntSize(
                size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1)
            )
            val glassBitmap = cpuBlurCache.glassRefraction(
                sourceBitmap = bitmap.asAndroidBitmap(),
                radiusPx = blurPx.roundToInt(),
                glass = glass,
                targetSize = targetSize
            )
            drawBitmapInCaptureRegion(glassBitmap.asImageBitmap(), result)
        }
        drawRect(glass.tint)
    }

    private fun ContentDrawScope.drawBlur(
        blur: BackdropFilter.Blur,
        result: BackdropState.CaptureResult?,
        bitmap: ImageBitmap?,
        density: Float,
        cpuBlurCache: CpuBlurCache
    ) {
        val blurPx = blur.blurRadiusIntensity * 2f * density
        if (bitmap != null && blurPx > 0f) {
            val blurred = cpuBlurCache.blur(bitmap.asAndroidBitmap(), blurPx.roundToInt())
            drawBitmapInCaptureRegion(blurred.asImageBitmap(), result)
        }
        drawRect(blur.tint)
    }
}
