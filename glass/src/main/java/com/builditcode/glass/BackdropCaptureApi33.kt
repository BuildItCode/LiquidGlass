package com.builditcode.glass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
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

/**
 * API 33+ backend: live GPU capture via [GraphicsLayer] with RenderEffect-based
 * blur/glass.
 *
 * The capture node's target layer is re-recorded only when the capture result or node
 * size changes; otherwise the existing display list is drawn as-is and the previously
 * applied render effect is left untouched, so static frames perform zero RenderNode
 * writes. Master layers are owned by the source node (ping-pong pair) and are never
 * released or retained here.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object BackdropCaptureApi33 : BackdropCaptureBackend {
    override val createsFallbackBitmap: Boolean = false
    override val requiresContinuousCapture: Boolean = false

    override fun usesHardwareLayerForSource(state: BackdropState): Boolean = true

    override fun configureCaptureLayer(layer: GraphicsLayer) {
        layer.compositingStrategy = CompositingStrategy.Offscreen
    }

    override fun onHardwareLayerRecorded(
        state: BackdropState,
        layer: GraphicsLayer,
        captureSize: IntSize
    ) {
        val session = state.beginHardwareCapture(captureSize)
        try {
            state.applyHardwareLayerCapture(layer, session)
        } catch (error: Exception) {
            state.onHardwareCaptureFailed(error, session)
        }
    }

    override fun ContentDrawScope.drawCapture(
        filter: BackdropFilter,
        shape: Shape,
        result: BackdropState.CaptureResult?,
        layer: GraphicsLayer?,
        density: Float,
        drawCache: CaptureDrawCache
    ) {
        val captureResult = result ?: return
        val targetLayer = layer ?: return
        val targetSize = size.toIntSize()

        if (drawCache.shouldRecord(captureResult, targetSize)) {
            targetLayer.record(size = targetSize) {
                translate(captureResult.drawOffset.x, captureResult.drawOffset.y) {
                    drawCaptureResult(captureResult)
                }
            }
            drawCache.markRecorded(captureResult, targetSize)
        }

        when (filter) {
            is BackdropFilter.Blur -> drawBlur(filter, targetLayer, density, drawCache)
            is BackdropFilter.Glass -> drawGlass(filter, shape, targetLayer, density, drawCache)
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
        density: Float,
        drawCache: CaptureDrawCache
    ) {
        val blurPx = blur.blurRadiusIntensity * 2f * density
        drawCache.applyRenderEffect(layer, drawCache.blurRenderEffect(blurPx))
        drawLayer(layer)
        drawRect(blur.tint)
    }

    private fun ContentDrawScope.drawGlass(
        glass: BackdropFilter.Glass,
        shape: Shape,
        layer: GraphicsLayer,
        density: Float,
        drawCache: CaptureDrawCache
    ) {
        val blurPx = glass.blurRadiusIntensity * 2f * density
        val cornerRadii = drawCache.cornerRadii(shape, size, layoutDirection, this)
        drawCache.applyGlassUniforms(glass, size.width, size.height, cornerRadii)
        drawCache.applyRenderEffect(layer, drawCache.glassRenderEffect(blurPx))
        drawLayer(layer)
    }
}