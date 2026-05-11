/**
 * Layered Backdrop Blur / Glass Effect System for Jetpack Compose.
 *
 * Architecture:
 * - Source/capture behavior is split into API 33+ and legacy (<33) backends.
 * - ALL modifiers are implemented as Modifier.Node to eliminate recomposition overhead.
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
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

internal fun Bitmap.safeRecycle() {
    try { if (!isRecycled) recycle() } catch (_: Exception) {}
}

internal fun ImageBitmap.safeRecycle() {
    val bitmap = asAndroidBitmap()
    bitmap.safeRecycle()
}

internal fun ImageBitmap.softwareCopyFromHardware(): ImageBitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val bitmap = asAndroidBitmap()
    if (bitmap.config != Bitmap.Config.HARDWARE) return null
    return try {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
    } catch (e: Exception) {
        Log.w("BackdropSnapshot", "Failed to copy hardware bitmap for CPU fallback", e)
        null
    }
}

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
// SDK IMPLEMENTATION ROUTING
// =============================================================================

internal val backdropCaptureBackend: BackdropCaptureBackend =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> BackdropCaptureApi33
        else -> BackdropCaptureLegacy
    }

internal interface BackdropCaptureBackend {
    val createsFallbackBitmap: Boolean

    fun usesHardwareLayerForSource(state: BackdropState): Boolean

    fun configureCaptureLayer(layer: GraphicsLayer)

    fun onHardwareLayerRecorded(
        state: BackdropState,
        layer: GraphicsLayer,
        captureSize: IntSize
    )

    fun ContentDrawScope.drawCapture(
        filter: BackdropFilter,
        result: BackdropState.CaptureResult?,
        layer: GraphicsLayer?,
        density: Float,
        cpuBlurCache: CpuBlurCache
    )
}

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
    DrawModifierNode,
    ObserverModifierNode {

    private var cachedState: BackdropState? = null
    private val picture = Picture()
    private var hardwareCaptureLayer: GraphicsLayer? = null

    fun updateLayerName(newName: String) {
        if (layerName != newName) { layerName = newName; cachedState = null }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val state = cachedState
            ?: currentValueOf(LocalBackdropLayerManager)?.getState(layerName).also { cachedState = it }
        state?.updateSourceRect(Rect(coordinates.positionInRoot(), coordinates.size.toSize()))
    }

    override fun onDetach() {
        hardwareCaptureLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        hardwareCaptureLayer = null
        cachedState = null
    }

    override fun onObservedReadsChanged() {
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val state = cachedState

        if (state == null) {
            drawContent()
            return
        }

        observeReads {
            state.sourceInvalidator
        }

        val w = size.width.roundToInt()
        val h = size.height.roundToInt()

        if (w > 0 && h > 0 && state.shouldCapture) {
            try {
                if (backdropCaptureBackend.usesHardwareLayerForSource(state)) {
                    val layer = hardwareCaptureLayer ?: requireGraphicsContext()
                        .createGraphicsLayer()
                        .also { hardwareCaptureLayer = it }
                    val captureSize = state.scaledSize(w, h)
                    val outerScope = this

                    drawContent()
                    layer.record(size = captureSize) {
                        scale(
                            scaleX = state.captureScaleFactor,
                            scaleY = state.captureScaleFactor,
                            pivot = Offset.Zero
                        ) {
                            outerScope.drawContent()
                        }
                    }
                    backdropCaptureBackend.onHardwareLayerRecorded(state, layer, captureSize)
                } else {
                    drawContent()
                    val canvas = picture.beginRecording(w, h)
                    val outerScope = this
                    draw(outerScope, layoutDirection, Canvas(canvas), size) {
                        outerScope.drawContent()
                    }
                    picture.endRecording()
                    state.onPictureRecorded(picture, w, h)
                }
            } catch (e: Exception) {
                Log.e("BackdropSource", "Failed to capture backdrop source", e)
            }
        } else {
            state.requestCaptureAfterPendingWork()
            drawContent()
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
    private val cpuBlurCache = CpuBlurCache()

    fun update(newName: String, newFilter: BackdropFilter, newAutoMove: Boolean) {
        val layerChanged = layerName != newName
        val shouldInvalidate = layerChanged || filter != newFilter || autoInvalidateOnMove != newAutoMove
        if (layerChanged) {
            cachedState?.unregisterRegion(regionId)
            layerName = newName
            cachedState = null
            lastResult = null
            cpuBlurCache.clear()
        }
        filter = newFilter
        autoInvalidateOnMove = newAutoMove
        if (shouldInvalidate) invalidateDraw()
    }

    override fun onAttach() {
        regionId = BackdropState.nextRegionId()
        graphicsLayer = requireGraphicsContext().createGraphicsLayer().also { layer ->
            backdropCaptureBackend.configureCaptureLayer(layer)
        }
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
            val state = cachedState
            state?.resultInvalidator
            lastResult = state?.getResult(regionId)
        }

        val result = lastResult

        if (result == null) {
            cachedState?.requestCaptureIfMissing()
            cpuBlurCache.clear()
        }

        backdropCaptureBackend.run {
            drawCapture(
                filter = filter,
                result = result,
                layer = graphicsLayer,
                density = density,
                cpuBlurCache = cpuBlurCache
            )
        }
        drawContent()
    }

    override fun onReset() {
        cachedState = null
        lastPosition = Offset.Unspecified
        lastResult = null
        cpuBlurCache.clear()
    }

    override fun onDetach() {
        cachedState?.unregisterRegion(regionId)
        graphicsLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        graphicsLayer = null
        cpuBlurCache.clear()
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
        val refraction: Float = 0.24f,
        val dispersion: Float = 0.2f,
        val edge: Float = 0.18f,
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
                s.setFloatUniform("dispersion", dispersion)
                s.setFloatUniform("edge", edge)
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
        val masterImage: ImageBitmap?,
        val masterLayer: GraphicsLayer?,
        val srcOffset: IntOffset,
        val srcSize: IntSize,
        val drawOffset: Offset,
        val drawSize: IntSize,
        val fallbackBitmap: ImageBitmap? = null,
        val captureScaleFactor: Float
    )

    private data class Region(val rect: Rect, val result: CaptureResult? = null)

    private data class CropGeometry(
        val srcOffset: IntOffset,
        val srcSize: IntSize,
        val drawOffset: Offset,
        val drawSize: IntSize
    )

    internal data class HardwareCaptureSession(
        val sourceRect: Rect,
        val scaledW: Int,
        val scaledH: Int
    )

    companion object {
        private val idCounter = AtomicInteger(0)
        fun nextRegionId(): Int = idCounter.getAndIncrement()
    }

    private val regions = mutableStateMapOf<Int, Region>()

    internal var sourceInvalidator by mutableLongStateOf(0L)
        private set

    internal var resultInvalidator by mutableLongStateOf(0L)
        private set

    private var masterImage: ImageBitmap? = null
    private var masterLayer: GraphicsLayer? = null
    private var reusableBitmap: Bitmap? = null

    private var masterSourceRect: Rect = Rect.Zero
    private var masterScaledW: Int = 0
    private var masterScaledH: Int = 0

    private var sourceRect: Rect = Rect.Zero
    private var useHardwareSnapshot = false
    private var lastCaptureTime = 0L
    private var captureRequested = false

    private var processingJob: Job? = null
    private var scheduledJob: Job? = null

    private val isProcessing: Boolean
        get() = processingJob?.isActive == true

    private val hasMasterCapture: Boolean
        get() = masterImage != null || masterLayer != null

    internal val shouldUseHardwareSnapshot: Boolean
        get() = useHardwareSnapshot

    internal val captureScaleFactor: Float
        get() = scaleFactor

    internal val captureScope: CoroutineScope
        get() = scope

    internal fun scaledSize(width: Int, height: Int): IntSize =
        IntSize(
            (width * scaleFactor).roundToInt().coerceAtLeast(1),
            (height * scaleFactor).roundToInt().coerceAtLeast(1)
        )

    fun requestCaptureIfMissing() {
        // Only request a recapture if we don't have a master image AND
        // we aren't already waiting for one to be processed or captured.
        if (!hasMasterCapture && !captureRequested && !isProcessing) {
            requestCapture()
        }
    }

    val shouldCapture: Boolean
        get() = regions.isNotEmpty()
                && processingJob?.isActive != true
                && debounced()
                && isUpdateEnabled()

    fun getResult(id: Int): CaptureResult? = regions[id]?.result

    fun requestCapture() {
        if (regions.isEmpty()) return
        captureRequested = true
        if (isProcessing) return
        invalidateOrSchedule()
    }

    internal fun requestCaptureAfterPendingWork() {
        if (regions.isEmpty() || !isUpdateEnabled() || (debounced() && !isProcessing)) return
        requestCapture()
    }

    internal fun registerRegion(id: Int, rect: Rect) {
        val existing = regions[id]
        if (existing != null && existing.rect == rect) return

        val isNew = existing == null
        val oldResult = existing?.result
        val master = masterImage
        val layer = masterLayer
        val geometry = if (master != null || layer != null) {
            cropGeometryForRegion(rect, masterSourceRect, masterScaledW, masterScaledH)
        } else {
            null
        }

        val canReuseResult = oldResult != null &&
                (master != null || layer != null) &&
                geometry != null &&
                oldResult.matches(master, layer, geometry)
        val newResult = when {
            canReuseResult -> oldResult
            (master != null || layer != null) && geometry != null -> captureResultForGeometry(
                masterImage = master,
                masterLayer = layer,
                geometry = geometry
            )
            else -> null
        }
        val visibleSame = existing != null && when {
            oldResult == null && newResult == null -> true
            canReuseResult -> true
            oldResult != null && newResult != null -> oldResult.visibleEquals(newResult)
            else -> false
        }

        if (!canReuseResult) {
            oldResult?.fallbackBitmap?.safeRecycle()
        }
        regions[id] = Region(rect, newResult)
        if (!visibleSame) resultInvalidator++
        if (isNew) requestCapture()
    }

    internal fun unregisterRegion(id: Int) {
        regions.remove(id)?.result?.fallbackBitmap?.safeRecycle()
    }

    internal fun updateSourceRect(rect: Rect) {
        if (sourceRect == rect) return
        sourceRect = rect
        requestCapture()
    }

    internal fun dispose() {
        processingJob?.cancel()
        scheduledJob?.cancel()
        regions.values.forEach { it.result?.fallbackBitmap?.safeRecycle() }
        regions.clear()
        reusableBitmap?.safeRecycle()
        reusableBitmap = null
        masterImage?.safeRecycle()
        masterImage = null
        masterLayer = null
    }

    internal fun onPictureRecorded(picture: Picture, width: Int, height: Int) {
        beginCapture()

        val scaledW = (width * scaleFactor).roundToInt().coerceAtLeast(1)
        val scaledH = (height * scaleFactor).roundToInt().coerceAtLeast(1)
        val currentSource = sourceRect

        val recyclable = reusableBitmap?.takeIf {
            !it.isRecycled && it.width == scaledW && it.height == scaledH
        }
        if (recyclable != null) {
            reusableBitmap = null
        } else {
            reusableBitmap?.safeRecycle()
            reusableBitmap = null
        }

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
                    applyMasterImage(masterImg, currentSource, scaledW, scaledH, reuseOldMaster = true)
                }
            } catch (e: CancellationException) {
                master.safeRecycle()
                throw e
            } catch (e: Exception) {
                master.safeRecycle()
                withContext(Dispatchers.Main) {
                    processingJob = null
                    if (isHardwareBitmapSoftwareFailure(e)) {
                        useHardwareSnapshot = true
                        lastCaptureTime = 0L
                        requestCapture()
                    } else {
                        Log.w("BackdropState", "Processing failed (${scaledW}x${scaledH})", e)
                        if (captureRequested) invalidateOrSchedule()
                    }
                }
            }
        }
    }

    internal fun beginHardwareCapture(captureSize: IntSize): HardwareCaptureSession {
        beginCapture()
        processingJob?.cancel()
        return HardwareCaptureSession(sourceRect, captureSize.width, captureSize.height)
    }

    internal fun setHardwareProcessingJob(job: Job) {
        processingJob = job
    }

    internal fun applyHardwareImageCapture(
        captured: ImageBitmap,
        session: HardwareCaptureSession
    ) {
        applyMasterImage(
            masterImg = captured,
            currentSource = session.sourceRect,
            scaledW = session.scaledW,
            scaledH = session.scaledH,
            reuseOldMaster = false
        )
    }

    internal fun applyHardwareLayerCapture(
        layer: GraphicsLayer,
        session: HardwareCaptureSession
    ) {
        applyMasterLayer(
            layer = layer,
            currentSource = session.sourceRect,
            scaledW = session.scaledW,
            scaledH = session.scaledH
        )
    }

    internal fun onHardwareCaptureFailed(
        error: Exception,
        session: HardwareCaptureSession
    ) {
        processingJob = null
        requestCapture()
        Log.w("BackdropState", "Hardware layer capture failed (${session.scaledW}x${session.scaledH})", error)
    }

    private fun applyMasterImage(
        masterImg: ImageBitmap,
        currentSource: Rect,
        scaledW: Int,
        scaledH: Int,
        reuseOldMaster: Boolean
    ) {
        val oldMaster = masterImage
        masterImage = masterImg
        masterLayer = null
        masterSourceRect = currentSource
        masterScaledW = scaledW
        masterScaledH = scaledH

        regions.forEach { (id, region) ->
            val result = cropForRegion(
                regionRect = region.rect,
                source = currentSource,
                masterImage = masterImg,
                masterLayer = null,
                scaledW = scaledW,
                scaledH = scaledH
            )
            region.result?.fallbackBitmap?.safeRecycle()
            regions[id] = region.copy(result = result)
        }
        resultInvalidator++

        recycleOrReuseOldMaster(oldMaster, reuseOldMaster)
        processingJob = null

        if (captureRequested) invalidateOrSchedule()
    }

    private fun applyMasterLayer(
        layer: GraphicsLayer,
        currentSource: Rect,
        scaledW: Int,
        scaledH: Int
    ) {
        val oldMaster = masterImage
        masterImage = null
        masterLayer = layer
        masterSourceRect = currentSource
        masterScaledW = scaledW
        masterScaledH = scaledH

        regions.forEach { (id, region) ->
            val result = cropForRegion(
                regionRect = region.rect,
                source = currentSource,
                masterImage = null,
                masterLayer = layer,
                scaledW = scaledW,
                scaledH = scaledH
            )
            region.result?.fallbackBitmap?.safeRecycle()
            regions[id] = region.copy(result = result)
        }
        resultInvalidator++

        recycleOrReuseOldMaster(oldMaster, reuseOldMaster = false)
        processingJob = null

        if (captureRequested) invalidateOrSchedule()
    }

    private fun beginCapture() {
        captureRequested = false
        lastCaptureTime = System.currentTimeMillis()
        scheduledJob?.cancel()
        scheduledJob = null
    }

    private fun recycleOrReuseOldMaster(oldMaster: ImageBitmap?, reuseOldMaster: Boolean) {
        if (!reuseOldMaster || oldMaster == null) {
            oldMaster?.safeRecycle()
            return
        }

        val bitmap = oldMaster.asAndroidBitmap()
        if (!bitmap.isRecycled && bitmap.isMutable) {
            reusableBitmap?.takeIf { it !== bitmap }?.safeRecycle()
            reusableBitmap = bitmap
        } else {
            oldMaster.safeRecycle()
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

    private fun cropGeometryForRegion(
        regionRect: Rect, source: Rect, scaledW: Int, scaledH: Int
    ): CropGeometry? {
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

        return CropGeometry(IntOffset(l, t), IntSize(w, h), drawOffset, drawSize)
    }

    private fun cropForRegion(
        regionRect: Rect,
        source: Rect,
        masterImage: ImageBitmap?,
        masterLayer: GraphicsLayer?,
        scaledW: Int,
        scaledH: Int
    ): CaptureResult? {
        val geometry = cropGeometryForRegion(regionRect, source, scaledW, scaledH) ?: return null
        return captureResultForGeometry(masterImage, masterLayer, geometry)
    }

    private fun captureResultForGeometry(
        masterImage: ImageBitmap?,
        masterLayer: GraphicsLayer?,
        geometry: CropGeometry
    ): CaptureResult {
        val fallback = if (backdropCaptureBackend.createsFallbackBitmap && masterImage != null) {
            cropBitmap(
                masterImage,
                Rect(
                    geometry.srcOffset.x.toFloat(),
                    geometry.srcOffset.y.toFloat(),
                    (geometry.srcOffset.x + geometry.srcSize.width).toFloat(),
                    (geometry.srcOffset.y + geometry.srcSize.height).toFloat()
                )
            )
        } else null

        return CaptureResult(
            masterImage = masterImage,
            masterLayer = masterLayer,
            srcOffset = geometry.srcOffset,
            srcSize = geometry.srcSize,
            drawOffset = geometry.drawOffset,
            drawSize = geometry.drawSize,
            fallbackBitmap = fallback,
            captureScaleFactor = scaleFactor
        )
    }

    private fun CaptureResult.matches(
        masterImage: ImageBitmap?,
        masterLayer: GraphicsLayer?,
        geometry: CropGeometry
    ): Boolean =
        this.masterImage === masterImage &&
                this.masterLayer === masterLayer &&
                srcOffset == geometry.srcOffset &&
                srcSize == geometry.srcSize &&
                drawOffset == geometry.drawOffset &&
                drawSize == geometry.drawSize

    private fun CaptureResult.visibleEquals(other: CaptureResult): Boolean =
        masterImage === other.masterImage &&
                masterLayer === other.masterLayer &&
                srcOffset == other.srcOffset &&
                srcSize == other.srcSize &&
                drawOffset == other.drawOffset &&
                drawSize == other.drawSize
}

// =============================================================================
// RENDERING
// =============================================================================

private val bitmapPaintThreadLocal = ThreadLocal<Paint>()

internal fun bitmapFilterPaint(): Paint {
    bitmapPaintThreadLocal.get()?.let { return it }
    return Paint().apply { isFilterBitmap = true }.also(bitmapPaintThreadLocal::set)
}

internal fun ContentDrawScope.drawBitmapInCaptureRegion(
    bitmap: ImageBitmap,
    result: BackdropState.CaptureResult?
) {
    val drawSize = result?.drawSize ?: IntSize(size.width.toInt(), size.height.toInt())
    val drawOffset = result?.drawOffset ?: Offset.Zero
    translate(drawOffset.x, drawOffset.y) {
        drawImage(bitmap, dstSize = drawSize)
    }
}

// =============================================================================
// CPU BLUR FALLBACK (< API 33)
// =============================================================================

internal class CpuBlurCache {
    private var source: Bitmap? = null
    private var radius: Int = 0
    private var result: Bitmap? = null

    private var glassSource: Bitmap? = null
    private var glassRadius: Int = 0
    private var glassRefraction: Float = -1f
    private var glassEdge: Float = -1f
    private var glassTargetSize: IntSize = IntSize.Zero
    private var glassResult: Bitmap? = null
    private var glassPixels = IntArray(0)
    private var glassOutput = IntArray(0)

    fun blur(sourceBitmap: Bitmap, radiusPx: Int): Bitmap {
        if (radiusPx < 1) {
            clear()
            return sourceBitmap
        }

        if (sourceBitmap === source && radiusPx == radius) {
            result?.let { if (!it.isRecycled) return it }
        }

        val blurred = applyStackBlur(sourceBitmap.copy(Bitmap.Config.ARGB_8888, true), radiusPx)
        result?.safeRecycle()
        source = sourceBitmap
        radius = radiusPx
        result = blurred
        return blurred
    }

    fun glassRefraction(
        sourceBitmap: Bitmap,
        radiusPx: Int,
        glass: BackdropFilter.Glass,
        targetSize: IntSize
    ): Bitmap {
        val radius = radiusPx.coerceAtLeast(0)
        if (
            sourceBitmap === glassSource &&
            radius == glassRadius &&
            glass.refraction == glassRefraction &&
            glass.edge == glassEdge &&
            targetSize == glassTargetSize
        ) {
            glassResult?.let { if (!it.isRecycled) return it }
        }

        val base = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val blurred = if (radius > 0) applyStackBlur(base, radius) else base
        val pixelCount = blurred.width * blurred.height
        if (glassPixels.size < pixelCount) glassPixels = IntArray(pixelCount)
        if (glassOutput.size < pixelCount) glassOutput = IntArray(pixelCount)

        val refracted = applyCpuGlassRefraction(
            bitmap = blurred,
            refraction = glass.refraction,
            edge = glass.edge,
            pixels = glassPixels,
            output = glassOutput
        )

        glassResult?.safeRecycle()
        glassSource = sourceBitmap
        glassRadius = radius
        glassRefraction = glass.refraction
        glassEdge = glass.edge
        glassTargetSize = targetSize
        glassResult = refracted
        return refracted
    }

    fun clear() {
        result?.safeRecycle()
        glassResult?.safeRecycle()
        source = null
        radius = 0
        result = null
        glassSource = null
        glassRadius = 0
        glassRefraction = -1f
        glassEdge = -1f
        glassTargetSize = IntSize.Zero
        glassResult = null
        glassPixels = IntArray(0)
        glassOutput = IntArray(0)
    }
}

// =============================================================================
// HELPERS
// =============================================================================

private fun isHardwareBitmapSoftwareFailure(error: Throwable): Boolean {
    if (error !is IllegalArgumentException) return false
    val message = error.message?.lowercase() ?: return false
    return "software rendering" in message && "hardware" in message
}

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
            bitmapFilterPaint()
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
