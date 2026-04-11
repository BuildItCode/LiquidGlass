/**
 * Layered Backdrop Blur / Glass Effect System for Jetpack Compose.
 *
 * Architecture:
 * - Source content is recorded into an Android Picture, then rasterized at a reduced scale.
 * - ALL modifiers are implemented as Modifier.Node to eliminate recomposition overhead.
 * - API 33+: AGSL RuntimeShader glass effect with refraction, dispersion, rim lighting.
 * - API 31+: Hardware-accelerated RenderEffect Gaussian blur.
 * - API < 31: CPU StackBlur fallback (single-entry cache).
 */
package com.builditcode.glass

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.FloatRange
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

// =============================================================================
// BITMAP LIFECYCLE
// =============================================================================

private fun Bitmap.safeRecycle() {
    try { if (!isRecycled) recycle() } catch (_: Exception) {}
}

private fun ImageBitmap.safeRecycle() = asAndroidBitmap().safeRecycle()

// =============================================================================
// LAYER MANAGER
// =============================================================================

@Composable
fun rememberBackdropManager(
    defaultScaleFactor: Float = 0.4f,
    defaultDebounceMs: Long = 32L
): BackdropLayerManager {
    val scope = rememberCoroutineScope()
    val manager = remember(scope) { BackdropLayerManager(scope, defaultScaleFactor, defaultDebounceMs) }
    DisposableEffect(manager) { onDispose { manager.disposeAll() } }
    return manager
}

@Stable
class BackdropLayerManager(
    private val scope: CoroutineScope,
    private val defaultScaleFactor: Float = 0.4f,
    private val defaultDebounceMs: Long = 32L
) {
    private val states = mutableMapOf<String, BackdropState>()

    /**
     * When false, [BackdropState.requestCapture] and [BackdropSourceNode.draw] are short-
     * circuited, so the existing master image is frozen. Consumers (capture nodes) still
     * read the last processed [BackdropState.CaptureResult], which lets overlays like modal sheets render
     * using the backdrop snapshot taken *before* they opened — avoiding a feedback loop
     * where the overlay's own content gets captured into its own blur.
     */
    var shouldUpdate: Boolean = true
        private set

    /** Freeze all backdrop captures. Last captured image keeps being used by consumers. */
    fun stopUpdates() {
        shouldUpdate = false
    }

    /** Resume captures and trigger one refresh so every layer catches up. */
    fun startUpdates() {
        if (shouldUpdate) return
        shouldUpdate = true
        invalidateAll()
    }

    fun getState(layerName: String): BackdropState =
        states.getOrPut(layerName) {
            BackdropState(scope, defaultScaleFactor, defaultDebounceMs) { shouldUpdate }
        }

    fun invalidateAll(excludeLayerName: String? = null) {
        states.forEach { (name, state) -> if (name != excludeLayerName) state.requestCapture() }
    }

    fun invalidate(layerName: String) {
        states[layerName]?.requestCapture()
    }

    fun disposeAll() {
        states.values.forEach { it.dispose() }
        states.clear()
    }
}

val LocalBackdropLayerManager = staticCompositionLocalOf<BackdropLayerManager?> { null }

// =============================================================================
// PUBLIC MODIFIERS
// =============================================================================

fun Modifier.layeredBackdropSource(layerName: String): Modifier =
    this.then(BackdropSourceElement(layerName))

fun Modifier.layeredBackdropCapture(
    layerName: String,
    shape: Shape = RoundedCornerShape(12.dp),
    padding: PaddingValues = PaddingValues(0.dp),
    filter: BackdropFilter = BackdropFilter.Blur(),
    autoInvalidateOnMove: Boolean = true
): Modifier = this
    .padding(padding)
    .clip(shape)
    .then(BackdropCaptureElement(layerName, filter, autoInvalidateOnMove))

// =============================================================================
// SOURCE NODE
// =============================================================================

private data class BackdropSourceElement(
    val layerName: String
) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(layerName)
    override fun update(node: BackdropSourceNode) { node.updateLayerName(layerName) }
    override fun InspectorInfo.inspectableProperties() {
        name = "layeredBackdropSource"
        properties["layerName"] = layerName
    }
}

private class BackdropSourceNode(
    var layerName: String
) : Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode,
    DrawModifierNode {

    private var cachedState: BackdropState? = null
    private val picture = Picture()

    fun updateLayerName(newName: String) {
        if (layerName != newName) { layerName = newName; cachedState = null }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val state = cachedState
            ?: currentValueOf(LocalBackdropLayerManager)?.getState(layerName).also { cachedState = it }
        state?.updateSourceRect(Rect(coordinates.positionInRoot(), coordinates.size.toSize()))
    }

    override fun ContentDrawScope.draw() {
        val state = cachedState

        // Always draw content to screen first — never skip a frame
        drawContent()

        if (state == null) return

        // Read snapshot state to bind invalidation to the draw phase
        state.sourceInvalidator

        val w = size.width.roundToInt()
        val h = size.height.roundToInt()

        if (w > 0 && h > 0 && state.shouldCapture) {
            try {
                val canvas = picture.beginRecording(w, h)
                val outerScope = this
                draw(outerScope, layoutDirection, Canvas(canvas), size) {
                    outerScope.drawContent()
                }
                picture.endRecording()
                state.onPictureRecorded(picture, w, h)
            } catch (e: Exception) {
                Log.e("BackdropSource", "Failed to record picture", e)
            }
        }
    }

    override fun onReset() { cachedState = null }
}

// =============================================================================
// CAPTURE NODE
// =============================================================================

private data class BackdropCaptureElement(
    val layerName: String,
    val filter: BackdropFilter,
    val autoInvalidateOnMove: Boolean
) : ModifierNodeElement<BackdropCaptureNode>() {
    override fun create() = BackdropCaptureNode(layerName, filter, autoInvalidateOnMove)
    override fun update(node: BackdropCaptureNode) {
        node.update(layerName, filter, autoInvalidateOnMove)
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "layeredBackdropCapture"
        properties["layerName"] = layerName
        properties["filter"] = filter
    }
}

private class BackdropCaptureNode(
    var layerName: String,
    var filter: BackdropFilter,
    var autoInvalidateOnMove: Boolean
) : Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode,
    DrawModifierNode,
    ObserverModifierNode {

    private var cachedState: BackdropState? = null
    private var regionId: Int = -1
    private var lastPosition = Offset.Unspecified
    private var graphicsLayer: GraphicsLayer? = null
    private var lastResult: BackdropState.CaptureResult? = null

    fun update(newName: String, newFilter: BackdropFilter, newAutoMove: Boolean) {
        if (layerName != newName) {
            cachedState?.unregisterRegion(regionId)
            layerName = newName
            cachedState = null
        }
        filter = newFilter
        autoInvalidateOnMove = newAutoMove
    }

    override fun onAttach() {
        regionId = BackdropState.nextRegionId()
        graphicsLayer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        cachedState?.unregisterRegion(regionId)
        graphicsLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        graphicsLayer = null
    }

    override fun onObservedReadsChanged() {
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val manager = currentValueOf(LocalBackdropLayerManager)
        val state = cachedState ?: manager?.getState(layerName).also { cachedState = it }

        val rect = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        state?.registerRegion(regionId, rect)

        if (autoInvalidateOnMove) {
            val pos = coordinates.positionInWindow()
            if (lastPosition != Offset.Unspecified && lastPosition != pos) {
                manager?.invalidateAll(excludeLayerName = layerName)
            }
            lastPosition = pos
        }
    }

    override fun ContentDrawScope.draw() {
        observeReads {
            lastResult = cachedState?.getResult(regionId)
        }

        val result = lastResult

        if (result == null) {
            cachedState?.requestCaptureIfMissing()
        }

        val layer = graphicsLayer ?: return run { drawContent() }

        layer.record(this.size.toIntSize()) {
            if (result != null) {
                translate(result.drawOffset.x, result.drawOffset.y) {
                    val fb = result.fallbackBitmap
                    if (fb != null) {
                        drawImage(image = fb, dstSize = result.drawSize)
                    } else {
                        drawImage(
                            image = result.masterImage,
                            srcOffset = result.srcOffset,
                            srcSize = result.srcSize,
                            dstSize = result.drawSize
                        )
                    }
                }
            }
        }

        val imageToBlur = result?.fallbackBitmap ?: result?.masterImage
        filter.apply(this, layer, imageToBlur, density)
        drawContent()
    }

    override fun onReset() {
        cachedState = null
        lastPosition = Offset.Unspecified
        lastResult = null
    }
}

// =============================================================================
// FILTER DEFINITIONS
// =============================================================================

@Stable
sealed interface BackdropFilter {

    @Stable
    data class Blur(
        @field:FloatRange(0.0, 10.0) val blurRadiusIntensity: Float = 5f,
        val tint: Color = Color.Transparent,
    ) : BackdropFilter {
        @Volatile private var cachedBlurPx: Float = -1f
        @Volatile private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

        @RequiresApi(Build.VERSION_CODES.S)
        internal fun getOrBuildBlurEffect(blurPx: Float): androidx.compose.ui.graphics.RenderEffect? {
            if (blurPx <= 0f) return null
            cachedEffect?.takeIf { cachedBlurPx == blurPx }?.let { return it }
            val effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
            cachedEffect = effect
            cachedBlurPx = blurPx
            return effect
        }
    }

    @Stable
    data class Glass(
        @field:FloatRange(0.0, 10.0) val blurRadiusIntensity: Float = 3f,
        val cornerRadiusDp: Float = 12f,
        val refraction: Float = 0.15f,
        val curve: Float = 0.2f,
        val dispersion: Float = 0.12f,
        val saturation: Float = 1.0f,
        val contrast: Float = 1.0f,
        val edge: Float = 0.2f,
        val tint: Color = Color.Transparent,
    ) : BackdropFilter {
        val shader: RuntimeShader? by lazy { createGlassShader() }

        private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null
        private var cachedBlurPx: Float = -1f
        private var lastDensity = -1f
        private var lastW = -1f
        private var lastH = -1f

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun getOrBuildRenderEffect(blurPx: Float): androidx.compose.ui.graphics.RenderEffect {
            cachedEffect?.takeIf { cachedBlurPx == blurPx }?.let { return it }

            val glassEffect = RenderEffect.createRuntimeShaderEffect(shader!!, "content")
            val blurEffect = if (blurPx > 0f)
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            else null

            val effect = if (blurEffect != null)
                RenderEffect.createChainEffect(glassEffect, blurEffect).asComposeRenderEffect()
            else
                glassEffect.asComposeRenderEffect()

            cachedEffect = effect
            cachedBlurPx = blurPx
            return effect
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun applyUniforms(w: Float, h: Float, density: Float) {
            val s = shader ?: return
            if (lastDensity != density) {
                s.setFloatUniform("cornerRadius", cornerRadiusDp * density)
                s.setFloatUniform("refraction", refraction)
                s.setFloatUniform("curve", curve)
                s.setFloatUniform("dispersion", dispersion)
                s.setFloatUniform("saturation", saturation)
                s.setFloatUniform("contrast", contrast)
                s.setFloatUniform("edge", edge)
                s.setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
                lastDensity = density
            }
            if (lastW != w || lastH != h) {
                s.setFloatUniform("resolution", w, h)
                s.setFloatUniform("lensCenter", w / 2f, h / 2f)
                s.setFloatUniform("lensSize", w, h)
                lastW = w
                lastH = h
            }
        }
    }
}

internal fun BackdropFilter.apply(
    scope: ContentDrawScope,
    layer: GraphicsLayer,
    bitmap: ImageBitmap?,
    density: Float,
) = when (this) {
    is BackdropFilter.Blur -> scope.drawBackdropBlur(this, layer, bitmap, density)
    is BackdropFilter.Glass -> scope.drawBackdropGlass(this, layer, bitmap, density)
}

// =============================================================================
// STATE
// =============================================================================

@Stable
class BackdropState internal constructor(
    private val scope: CoroutineScope,
    private val scaleFactor: Float,
    private val debounceMs: Long,
    private val isUpdateEnabled: () -> Boolean = { true },
) {
    data class CaptureResult(
        val masterImage: ImageBitmap,
        val srcOffset: IntOffset,
        val srcSize: IntSize,
        val drawOffset: Offset,
        val drawSize: IntSize,
        val fallbackBitmap: ImageBitmap? = null
    )

    private data class Region(val rect: Rect, val result: CaptureResult? = null)

    companion object {
        private val idCounter = AtomicInteger(0)
        fun nextRegionId(): Int = idCounter.getAndIncrement()
    }

    private val regions = mutableStateMapOf<Int, Region>()

    internal var sourceInvalidator by mutableLongStateOf(0L)
        private set

    private var masterImage: ImageBitmap? = null

    private var reusableBitmap: Bitmap? = null

    private var masterSourceRect: Rect = Rect.Zero
    private var masterScaledW: Int = 0
    private var masterScaledH: Int = 0

    private var sourceRect: Rect = Rect.Zero
    private var lastCaptureTime = 0L
    private var captureRequested = false

    private var processingJob: Job? = null
    private var scheduledJob: Job? = null

    private val isProcessing: Boolean
        get() = processingJob?.isActive == true

    private val hasMasterImage: Boolean
        get() = masterImage != null

    fun requestCaptureIfMissing() {
        // Only request a recapture if we don't have a master image AND
        // we aren't already waiting for one to be processed or captured.
        if (!hasMasterImage && !captureRequested && !isProcessing) {
            requestCapture()
        }
    }

    val shouldCapture: Boolean
        get() = regions.isNotEmpty()
                && captureRequested
                && processingJob?.isActive != true
                && debounced()
                && isUpdateEnabled()

    fun getResult(id: Int): CaptureResult? = regions[id]?.result

    fun requestCapture() {
        if (regions.isEmpty()) return
        captureRequested = true
        invalidateOrSchedule()
    }

    internal fun registerRegion(id: Int, rect: Rect) {
        val existing = regions[id]
        if (existing != null && existing.rect == rect) return

        val isNew = existing == null
        regions[id] = Region(rect)

        // Attempt a synchronous crop if we already have a master image.
        val master = masterImage
        if (master != null) {
            cropForRegion(rect, masterSourceRect, master, masterScaledW, masterScaledH)?.let {
                regions[id] = Region(rect, it)
            }
        }
        if (isNew) requestCapture()
    }

    internal fun unregisterRegion(id: Int) {
        regions.remove(id)
    }

    internal fun updateSourceRect(rect: Rect) {
        if (sourceRect == rect) return
        sourceRect = rect
        requestCapture()
    }

    internal fun dispose() {
        processingJob?.cancel()
        scheduledJob?.cancel()
        regions.clear()
        masterImage = null
    }

    internal fun onPictureRecorded(picture: Picture, width: Int, height: Int) {
        captureRequested = false
        lastCaptureTime = System.currentTimeMillis()

        val scaledW = (width * scaleFactor).roundToInt().coerceAtLeast(1)
        val scaledH = (height * scaleFactor).roundToInt().coerceAtLeast(1)
        val currentSource = sourceRect

        val recyclable = reusableBitmap?.takeIf { !it.isRecycled && it.width == scaledW && it.height == scaledH }
        if (recyclable != null) reusableBitmap = null

        processingJob?.cancel()
        processingJob = scope.launch(Dispatchers.Default) {
            val master = recyclable?.also { it.eraseColor(0) } ?: createBitmap(scaledW, scaledH)
            try {
                android.graphics.Canvas(master).apply {
                    scale(scaleFactor, scaleFactor)
                    drawPicture(picture)
                }
                if (!isActive) { master.safeRecycle(); return@launch }

                val masterImg = master.asImageBitmap()

                withContext(Dispatchers.Main) {
                    val oldMaster = this@BackdropState.masterImage
                    this@BackdropState.masterImage = masterImg
                    this@BackdropState.masterSourceRect = currentSource
                    this@BackdropState.masterScaledW = scaledW
                    this@BackdropState.masterScaledH = scaledH

                    regions.forEach { (id, region) ->
                        val result = cropForRegion(region.rect, currentSource, masterImg, scaledW, scaledH)
                        region.result?.fallbackBitmap?.safeRecycle()
                        regions[id] = region.copy(result = result)
                    }

                    reusableBitmap = oldMaster?.asAndroidBitmap()?.takeIf { !it.isRecycled }
                    processingJob = null

                    if (captureRequested) invalidateOrSchedule()
                }
            } catch (e: CancellationException) {
                master.safeRecycle(); throw e
            } catch (e: Exception) {
                master.safeRecycle()
                Log.w("BackdropState", "Processing failed (${scaledW}x${scaledH})", e)
            }
        }
    }
    private fun debounced(): Boolean =
        (System.currentTimeMillis() - lastCaptureTime) >= debounceMs

    private fun invalidateOrSchedule() {
        if (debounced()) {
            sourceInvalidator++
        } else if (scheduledJob?.isActive != true) {
            scheduledJob = scope.launch {
                val remaining = (debounceMs - (System.currentTimeMillis() - lastCaptureTime)).coerceAtLeast(0L)
                if (remaining > 0) delay(remaining)
                sourceInvalidator++
            }
        }
    }

    private fun cropForRegion(
        regionRect: Rect, source: Rect, master: ImageBitmap, scaledW: Int, scaledH: Int
    ): CaptureResult? {
        val intersection = regionRect.intersect(source)
        if (intersection.isEmpty) return null

        val rawCrop = intersection.translate(-source.left, -source.top).scale(scaleFactor)

        val l = rawCrop.left.roundToInt().coerceIn(0, scaledW)
        val t = rawCrop.top.roundToInt().coerceIn(0, scaledH)
        val r = rawCrop.right.roundToInt().coerceIn(0, scaledW)
        val b = rawCrop.bottom.roundToInt().coerceIn(0, scaledH)
        val w = r - l; val h = b - t
        if (w <= 0 || h <= 0) return null

        val errorX = (l - rawCrop.left) / scaleFactor
        val errorY = (t - rawCrop.top) / scaleFactor
        val drawOffset = (intersection.topLeft - regionRect.topLeft) - Offset(errorX, errorY)
        val drawSize = IntSize((w / scaleFactor).roundToInt(), (h / scaleFactor).roundToInt())

        val fallback = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            cropBitmap(master, Rect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))
        } else null

        return CaptureResult(master, IntOffset(l, t), IntSize(w, h), drawOffset, drawSize, fallback)
    }
}

// =============================================================================
// RENDERING
// =============================================================================

private val cropPaint = Paint().apply { isFilterBitmap = true }

internal fun ContentDrawScope.drawBackdropGlass(
    glass: BackdropFilter.Glass, layer: GraphicsLayer, bitmap: ImageBitmap?, density: Float,
) {
    val blurPx = glass.blurRadiusIntensity * 2f * density

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        glass.applyUniforms(size.width, size.height, density)
        layer.renderEffect = glass.getOrBuildRenderEffect(blurPx)
        drawLayer(layer)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (blurPx > 0f) {
            layer.renderEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
        drawLayer(layer)
        drawRect(glass.tint)
    } else {
        if (bitmap != null && blurPx > 0f) {
            val blurred = cpuBlur(bitmap.asAndroidBitmap(), blurPx.roundToInt())
            drawImage(blurred.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
        drawRect(glass.tint)
    }
}

internal fun ContentDrawScope.drawBackdropBlur(
    blur: BackdropFilter.Blur, layer: GraphicsLayer, bitmap: ImageBitmap?, density: Float,
) {
    val blurPx = blur.blurRadiusIntensity * 2f * density

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        blur.getOrBuildBlurEffect(blurPx)?.let { layer.renderEffect = it }
        drawLayer(layer)
    } else if (bitmap != null && blurPx > 0f) {
        val blurred = cpuBlur(bitmap.asAndroidBitmap(), blurPx.roundToInt())
        drawImage(blurred.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
    drawRect(blur.tint)
}

// =============================================================================
// CPU BLUR FALLBACK (< API 31)
// =============================================================================

private var cachedBlurSource: Bitmap? = null
private var cachedBlurRadius: Int = 0
private var cachedBlurResult: Bitmap? = null

private fun cpuBlur(source: Bitmap, radius: Int): Bitmap {
    if (radius < 1) return source
    if (source === cachedBlurSource && radius == cachedBlurRadius) {
        cachedBlurResult?.let { if (!it.isRecycled) return it }
    }
    val blurred = applyStackBlur(source.copy(Bitmap.Config.ARGB_8888, true), radius)
    cachedBlurSource = source
    cachedBlurRadius = radius
    cachedBlurResult = blurred
    return blurred
}

// =============================================================================
// HELPERS
// =============================================================================

private fun Rect.scale(factor: Float): Rect =
    Rect(left * factor, top * factor, right * factor, bottom * factor)

private fun cropBitmap(source: ImageBitmap, rect: Rect): ImageBitmap? {
    val l = rect.left.roundToInt().coerceIn(0, source.width)
    val t = rect.top.roundToInt().coerceIn(0, source.height)
    val r = rect.right.roundToInt().coerceIn(0, source.width)
    val b = rect.bottom.roundToInt().coerceIn(0, source.height)
    val w = r - l; val h = b - t
    if (w <= 0 || h <= 0) return null

    return try {
        val out = createBitmap(w, h)
        android.graphics.Canvas(out).drawBitmap(
            source.asAndroidBitmap(),
            android.graphics.Rect(l, t, r, b),
            android.graphics.Rect(0, 0, w, h),
            cropPaint
        )
        out.asImageBitmap()
    } catch (e: Exception) {
        Log.w("BackdropCrop", "Failed to crop bitmap (${w}x${h})", e)
        null
    }
}


private fun createGlassShader(): RuntimeShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(GLASS_SHADER)
    else null