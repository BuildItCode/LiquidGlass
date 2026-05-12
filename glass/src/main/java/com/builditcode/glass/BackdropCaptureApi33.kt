package com.builditcode.glass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object BackdropCaptureApi33 : BackdropCaptureBackend {
    override val createsFallbackBitmap: Boolean = false
    override val retainsHardwareCaptureLayer: Boolean = true

    override fun usesHardwareLayerForSource(state: BackdropState): Boolean = true

    override fun configureCaptureLayer(layer: GraphicsLayer) {
        layer.compositingStrategy = CompositingStrategy.Offscreen
    }

    override fun onHardwareLayerRecorded(
        state: BackdropState,
        layer: GraphicsLayer,
        captureSize: IntSize,
        preferImageSnapshot: Boolean
    ) {
        val session = state.beginHardwareCapture(captureSize)
        if (preferImageSnapshot) {
            publishImageSnapshot(state, layer, session)
            return
        }

        val ownsLayer = AtomicBoolean(true)

        fun releaseOwnedLayer() {
            if (ownsLayer.getAndSet(false)) {
                state.releaseHardwareCaptureLayer(layer)
            }
        }

        val job = state.captureScope.launch(Dispatchers.Main) {
            try {
                state.applyHardwareLayerCapture(layer, session)
                ownsLayer.set(false)
            } catch (error: CancellationException) {
                releaseOwnedLayer()
                throw error
            } catch (error: Exception) {
                releaseOwnedLayer()
                state.onHardwareCaptureFailed(error, session)
            }
        }
        job.invokeOnCompletion { error ->
            if (error is CancellationException) releaseOwnedLayer()
        }
        state.setHardwareProcessingJob(job)
    }

    private fun publishImageSnapshot(
        state: BackdropState,
        layer: GraphicsLayer,
        session: BackdropState.HardwareCaptureSession
    ) {
        val ownsLayer = AtomicBoolean(true)

        fun releaseOwnedLayer() {
            if (ownsLayer.getAndSet(false)) {
                state.releaseHardwareCaptureLayer(layer)
            }
        }

        val job = state.captureScope.launch(Dispatchers.Main) {
            var captured: ImageBitmap? = null
            try {
                captured = layer.toImageBitmap()
                state.applyHardwareImageCapture(captured, session)
                captured = null
                releaseOwnedLayer()
            } catch (error: CancellationException) {
                captured?.safeRecycle()
                releaseOwnedLayer()
                throw error
            } catch (error: Exception) {
                captured?.safeRecycle()
                releaseOwnedLayer()
                state.onHardwareCaptureFailed(error, session)
            }
        }
        job.invokeOnCompletion { error ->
            if (error is CancellationException) releaseOwnedLayer()
        }
        state.setHardwareProcessingJob(job)
    }

    override fun ContentDrawScope.drawCapture(
        filter: BackdropFilter,
        result: BackdropState.CaptureResult?,
        layer: GraphicsLayer?,
        density: Float,
        cpuBlurCache: CpuBlurCache
    ) {
        val captureResult = result ?: return
        val targetLayer = layer ?: return

        targetLayer.renderEffect = null
        targetLayer.record(size.toIntSize()) {
            translate(captureResult.drawOffset.x, captureResult.drawOffset.y) {
                drawCaptureResult(captureResult)
            }
        }

        when (filter) {
            is BackdropFilter.Blur -> drawBlur(filter, targetLayer, density)
            is BackdropFilter.Glass -> drawGlass(filter, targetLayer, density)
        }
    }

    private fun DrawScope.drawCaptureResult(result: BackdropState.CaptureResult) {
        when {
            result.masterLayer != null -> drawLayerBackedResult(result)
            result.fallbackBitmap != null -> drawImage(
                image = result.fallbackBitmap,
                dstSize = result.drawSize
            )
            result.masterImage != null -> drawImageBackedResult(result.masterImage, result)
        }
    }

    private fun DrawScope.drawLayerBackedResult(result: BackdropState.CaptureResult) {
        val layer = result.masterLayer ?: return
        val scale = result.captureScaleFactor.coerceAtLeast(0.001f)
        clipRect(
            left = 0f,
            top = 0f,
            right = result.drawSize.width.toFloat(),
            bottom = result.drawSize.height.toFloat()
        ) {
            scale(1f / scale, 1f / scale, pivot = Offset.Zero) {
                translate(
                    -result.srcOffset.x.toFloat(),
                    -result.srcOffset.y.toFloat()
                ) {
                    drawLayer(layer)
                }
            }
        }
    }

    private fun DrawScope.drawImageBackedResult(
        image: ImageBitmap,
        result: BackdropState.CaptureResult
    ) {
        drawImage(
            image = image,
            srcOffset = result.srcOffset,
            srcSize = result.srcSize,
            dstSize = result.drawSize
        )
    }

    private fun ContentDrawScope.drawBlur(
        blur: BackdropFilter.Blur,
        layer: GraphicsLayer,
        density: Float
    ) {
        val blurPx = blur.blurRadiusIntensity * 2f * density
        layer.renderEffect = blur.getOrBuildBlurEffect(blurPx)
        drawLayer(layer)
        drawRect(blur.tint)
    }

    private fun ContentDrawScope.drawGlass(
        glass: BackdropFilter.Glass,
        layer: GraphicsLayer,
        density: Float
    ) {
        val blurPx = glass.blurRadiusIntensity * 2f * density
        glass.applyUniforms(size.width, size.height, density)
        layer.renderEffect = glass.getOrBuildRenderEffect(blurPx)
        drawLayer(layer)
    }
}
