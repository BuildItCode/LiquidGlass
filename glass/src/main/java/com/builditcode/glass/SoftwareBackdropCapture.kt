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
 * Software backend: [android.graphics.Picture]/bitmap capture with CPU blur.
 *
 * Used whenever the hardware capture path ([HardwareBackdropCapture]) is unavailable — below
 * API 33, or when hardware acceleration is disabled for the backdrop. See
 * [backdropCaptureBackend] / [BackdropState.backend] for selection.
 *
 * The master is stack-blurred once per distinct region radius on [Dispatchers.Default].
 * Region crops and glass refraction are prepared in that same worker, so steady-state
 * draws are plain bitmap blits. Movement and filter changes during processing may still
 * need a transitional draw-time effect. A pre-blurred fallback with a stale radius keeps
 * its previous blur level until the requested recapture lands. On API 28+, hardware pictures
 * render and read back on the worker. Sources that cannot use that path promote to a
 * hardware-layer snapshot; its software copy and filtering run on the worker, while the
 * platform-dependent snapshot call may execute synchronously on Main.
 */
internal object SoftwareBackdropCapture : BackdropCaptureBackend {
    override val createsFallbackBitmap: Boolean = true
    override val requiresContinuousCapture: Boolean = true

    override fun usesHardwareLayerForSource(state: BackdropState): Boolean =
        state.shouldUseHardwareSnapshot && !state.isHardwareAccelerationDisabled

    override fun configureCaptureLayer(layer: GraphicsLayer) = Unit

    override fun onHardwareLayerRecorded(
        state: BackdropState,
        layer: GraphicsLayer,
        captureSize: IntSize,
        bitmapLease: BackdropBitmapLease?
    ) {
        val session = state.beginHardwareCapture(captureSize)
        val requests = state.snapshotSoftwareRegions()
        val reference = state.softwareCaptureReference(session, requests)
        val readbackLease = bitmapLease?.copy()
        state.setHardwareProcessingJob(
            state.captureScope.launch(Dispatchers.Main) {
                var master: ImageBitmap? = null
                var prepared: PreparedSoftwareCapture? = null
                var applied = false
                var unchanged = false
                try {
                    val snapshot = layer.toImageBitmap()
                    master = snapshot
                    withContext(Dispatchers.Default) {
                        val software = snapshot.softwareCopyFromHardware()
                        val masterBitmap = if (software != null) {
                            master = software
                            snapshot.safeRecycle()
                            software
                        } else {
                            snapshot
                        }
                        // Publish ownership before the cancellable return to Main; otherwise
                        // prompt cancellation can discard the result and leak both allocations.
                        unchanged = reference?.matches(masterBitmap) == true
                        if (!unchanged) prepared = state.prepareSoftwareCapture(masterBitmap, requests, session)
                    }
                    if (!isActive) {
                        return@launch
                    }
                    reference?.close()
                    if (unchanged) {
                        state.completeUnchangedSoftwareCapture(checkNotNull(master), reuse = false)
                        applied = true
                        return@launch
                    }
                    state.applyHardwareImageCapture(checkNotNull(master), checkNotNull(prepared).blurredMasters, session, prepared)
                    applied = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.onHardwareCaptureFailed(e, session)
                } finally {
                    if (!applied) {
                        master?.safeRecycle()
                        prepared?.recycle()
                    }
                }
            }.also { job -> job.invokeOnCompletion {
                readbackLease?.close()
                reference?.close()
            } }
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
            val prepared = result?.preparedGlassFor(glass, residualRadiusPx)
            val glassBitmap = if (prepared != null) {
                cpuBlur.clearResults()
                prepared
            } else {
                cpuBlur.glassRefraction(
                    sourceBitmap = bitmap.asAndroidBitmap(),
                    radiusPx = residualRadiusPx,
                    glass = glass,
                    targetSize = targetSize
                ).asImageBitmap()
            }
            drawBitmapInCaptureRegion(glassBitmap, result)
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
