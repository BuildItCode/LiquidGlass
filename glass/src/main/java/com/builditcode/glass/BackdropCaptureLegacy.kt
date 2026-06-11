package com.builditcode.glass

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Legacy (< API 33) backend: Picture/bitmap capture with CPU blur.
 *
 * Region fallbacks are crops of a master that was already stack-blurred per distinct
 * region radius on [Dispatchers.Default], so steady-state draws are plain bitmap blits
 * with no main-thread blur. The only draw-time CPU work is the transitional case where
 * a region's radius changed after the last capture: an unblurred fallback gets a one-off
 * draw-time blur, and a pre-blurred fallback with a stale radius keeps its previous blur
 * level until the requested recapture lands. Hardware snapshot readback and the
 * software-config copy both run off the main thread.
 */
internal object BackdropCaptureLegacy : BackdropCaptureBackend {
    override val createsFallbackBitmap: Boolean = true
    override val requiresContinuousCapture: Boolean = true

    override fun usesHardwareLayerForSource(state: BackdropState): Boolean =
        state.shouldUseHardwareSnapshot

    override fun configureCaptureLayer(layer: GraphicsLayer) = Unit

    override fun onHardwareLayerRecorded(
        state: BackdropState,
        layer: GraphicsLayer,
        captureSize: IntSize
    ) {
        val session = state.beginHardwareCapture(captureSize)
        val blurRadii = state.pendingCpuBlurRadii()
        state.setHardwareProcessingJob(
            state.captureScope.launch(Dispatchers.Main) {
                var master: ImageBitmap? = null
                var blurredMasters: Map<Int, ImageBitmap> = emptyMap()
                var applied = false
                try {
                    val snapshot = layer.toImageBitmap()
                    master = snapshot
                    val prepared = withContext(Dispatchers.Default) {
                        val software = snapshot.softwareCopyFromHardware()
                        val masterBitmap = if (software != null) {
                            snapshot.safeRecycle()
                            software
                        } else {
                            snapshot
                        }
                        masterBitmap to createBlurredMasters(masterBitmap.asAndroidBitmap(), blurRadii)
                    }
                    master = prepared.first
                    blurredMasters = prepared.second
                    if (!isActive) {
                        master?.safeRecycle()
                        blurredMasters.values.forEach { it.safeRecycle() }
                        return@launch
                    }
                    state.applyHardwareImageCapture(prepared.first, prepared.second, session)
                    applied = true
                } catch (e: CancellationException) {
                    if (!applied) {
                        master?.safeRecycle()
                        blurredMasters.values.forEach { it.safeRecycle() }
                    }
                    throw e
                } catch (e: Exception) {
                    if (!applied) {
                        master?.safeRecycle()
                        blurredMasters.values.forEach { it.safeRecycle() }
                    }
                    state.onHardwareCaptureFailed(e, session)
                }
            }
        )
    }

    override fun ContentDrawScope.drawCapture(
        filter: BackdropFilter,
        shape: Shape,
        result: BackdropState.CaptureResult?,
        layer: GraphicsLayer?,
        density: Float,
        drawCache: CaptureDrawCache
    ) {
        val bitmap = result?.fallbackBitmap
        when (filter) {
            is BackdropFilter.Blur -> drawBlur(filter, result, bitmap, density, drawCache.cpuBlur)
            is BackdropFilter.Glass -> drawGlass(filter, result, bitmap, density, drawCache.cpuBlur)
        }
    }

    private fun ContentDrawScope.drawGlass(
        glass: BackdropFilter.Glass,
        result: BackdropState.CaptureResult?,
        bitmap: ImageBitmap?,
        density: Float,
        cpuBlur: CpuBlurCache
    ) {
        if (bitmap != null) {
            val wantedRadiusPx = (glass.blurRadiusIntensity * 2f * density).roundToInt()
            val preBlurredRadiusPx = result?.fallbackBlurRadiusPx ?: 0
            val residualRadiusPx = if (preBlurredRadiusPx == 0) wantedRadiusPx else 0
            val targetSize = result?.drawSize ?: IntSize(
                size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1)
            )
            val glassBitmap = cpuBlur.glassRefraction(
                sourceBitmap = bitmap.asAndroidBitmap(),
                radiusPx = residualRadiusPx,
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
        cpuBlur: CpuBlurCache
    ) {
        if (bitmap != null) {
            val wantedRadiusPx = (blur.blurRadiusIntensity * 2f * density).roundToInt()
            val preBlurredRadiusPx = result?.fallbackBlurRadiusPx ?: 0
            val toDraw = if (wantedRadiusPx > 0 && preBlurredRadiusPx == 0) {
                cpuBlur.blur(bitmap.asAndroidBitmap(), wantedRadiusPx).asImageBitmap()
            } else {
                bitmap
            }
            drawBitmapInCaptureRegion(toDraw, result)
        }
        drawRect(blur.tint)
    }
}