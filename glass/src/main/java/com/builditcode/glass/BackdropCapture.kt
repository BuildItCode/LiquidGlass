/**
 * Layered Backdrop Blur / Glass Effect System for Jetpack Compose.
 *
 * Architecture:
 * - Source/capture behavior is split into API 33+ and legacy (<33) backends.
 * - ALL modifiers are implemented as Modifier.Node to eliminate recomposition overhead.
 *
 * Invalidation model:
 * - Capture regions live in a plain map; each region exposes its result through its own
 *   snapshot state. Moving one glass component re-crops and invalidates only that
 *   component, and never invalidates the source's draw (the master capture already
 *   covers the whole source, so movement is a local crop update).
 * - The source recaptures only when its own content invalidates its draw or when a
 *   capture is explicitly requested through [BackdropState.sourceInvalidator].
 *
 * GPU resources:
 * - Each source owns two ping-pong [GraphicsLayer]s and alternates recordings between
 *   them, so no layer is ever created, released, or re-recorded while the RenderThread
 *   may still reference it as the active master. Layers are released only on node
 *   detach, deferred by two frames via [BackdropState.releaseLayersAfterFrames].
 * - All shader/effect state is per capture node ([CaptureDrawCache]); [BackdropFilter]
 *   values are pure immutable specs, so sharing one filter instance across nodes is safe.
 *
 * Legacy (< API 33) path:
 * - The scaled master bitmap is stack-blurred once per distinct region blur radius on
 *   [Dispatchers.Default] at capture time; region fallbacks are crops of the pre-blurred
 *   master, so no CPU blur runs on the main thread during steady-state draws. If a
 *   filter's blur radius changes, a recapture is requested and the previous blur level
 *   is shown until it lands.
 */
package com.builditcode.glass

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
import kotlin.math.min
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

/**
 * Produces one stack-blurred copy of [master] per requested radius.
 *
 * Used by the legacy backend to pre-blur the scaled master on a background dispatcher
 * so per-region fallbacks become simple crops at draw time. Failed copies (e.g. copies
 * of hardware-config bitmaps on constrained devices) are skipped rather than failing
 * the capture.
 */
internal fun createBlurredMasters(master: Bitmap, radii: Set<Int>): Map<Int, ImageBitmap> {
    if (radii.isEmpty()) return emptyMap()
    val out = HashMap<Int, ImageBitmap>(radii.size)
    for (radius in radii) {
        val blurred = runCatching {
            applyStackBlur(master.copy(Bitmap.Config.ARGB_8888, true), radius)
        }.getOrNull() ?: continue
        out[radius] = blurred.asImageBitmap()
    }
    return out
}

// =============================================================================
// LAYER MANAGER
// =============================================================================

/**
 * Creates and remembers the backdrop manager used by [layeredBackdropSource] and
 * [layeredBackdropCapture].
 *
 * Provide this through [LocalBackdropLayerManager] when manually wiring layers, or let
 * [TriLevelLayout] / [QuadLevelLayout] create one for you. A manager owns the capture
 * state for each layer name, applies the default capture scale/debounce, and releases
 * native bitmap/layer resources when the composition leaves.
 *
 * Important: captures are directional. A glass component should sample a source layer
 * below or behind it, not the same layer that currently contains the component. For
 * example, foreground content can sample "background", and overlay content can sample
 * "foreground" or "background"; foreground content should not sample "foreground".
 * Same-layer capture can create a feedback loop and is intentionally not a supported
 * live-glass topology.
 *
 * @param defaultScaleFactor Internal capture resolution scale used by new layer states.
 * Lower values reduce capture/blur cost at the expense of detail.
 * @param defaultDebounceMs Minimum interval between source recaptures for new layer states.
 */
@Composable
fun rememberBackdropManager(
    defaultScaleFactor: Float = 0.4f,
    defaultDebounceMs: Long = 16L
): BackdropLayerManager {
    val scope = rememberCoroutineScope()
    val manager = remember(scope) { BackdropLayerManager(scope, defaultScaleFactor, defaultDebounceMs) }
    DisposableEffect(manager) { onDispose { manager.disposeAll() } }
    return manager
}

/**
 * Coordinates backdrop source captures by layer name.
 *
 * Most apps should use [rememberBackdropManager] instead of constructing this directly.
 * The manager is useful when multiple manually placed [layeredBackdropSource] and
 * [layeredBackdropCapture] modifiers need to share named capture state.
 *
 * Keep capture names directional: do not place a capture component inside a source and
 * ask it to sample that same source name. Use a lower/previous layer instead.
 */
@Stable
class BackdropLayerManager(
    private val scope: CoroutineScope,
    private val defaultScaleFactor: Float,
    private val defaultDebounceMs: Long
) {
    private val states = mutableMapOf<String, BackdropState>()

    /**
     * When false, [BackdropState.requestCapture] and the source node's capture path are
     * short-circuited, so the existing master image is frozen. Consumers (capture nodes)
     * still read the last processed [BackdropState.CaptureResult], which lets overlays
     * like modal sheets render using the backdrop snapshot taken *before* they opened —
     * avoiding a feedback loop where the overlay's own content gets captured into its
     * own blur.
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

    /**
     * Returns the internal capture state for [layerName], creating it on first use.
     *
     * This is primarily used by the source/capture modifiers. Public callers normally
     * only need [invalidate], [invalidateAll], [stopUpdates], or [startUpdates].
     */
    fun getState(layerName: String): BackdropState =
        states.getOrPut(layerName) {
            BackdropState(scope, defaultScaleFactor, defaultDebounceMs) { shouldUpdate }
        }

    /**
     * Request a fresh capture for every known layer.
     *
     * This can also prime a layer before its first capture region is attached, which is
     * useful before showing an overlay that needs glass immediately on first open.
     */
    fun invalidateAll(excludeLayerName: String? = null) {
        states.forEach { (name, state) ->
            if (name != excludeLayerName) state.requestCapture(force = true)
        }
    }

    /**
     * Request a fresh capture for [layerName].
     *
     * If the layer already exists, this can prime the source even when no capture node is
     * currently registered, so a newly opened overlay can use the latest backdrop.
     */
    fun invalidate(layerName: String) {
        states[layerName]?.requestCapture(force = true)
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
        shape: Shape,
        result: BackdropState.CaptureResult?,
        layer: GraphicsLayer?,
        density: Float,
        drawCache: CaptureDrawCache
    )
}

private val recordingBackdropSources = ThreadLocal<ArrayDeque<String>>()
private val drawingBackdropSources = ThreadLocal<ArrayDeque<String>>()

private inline fun <T> recordingBackdropSource(layerName: String, block: () -> T): T {
    val stack = recordingBackdropSources.get() ?: ArrayDeque<String>().also(recordingBackdropSources::set)
    stack.addLast(layerName)
    return try {
        block()
    } finally {
        stack.removeLast()
    }
}

private fun isRecordingAnyBackdropSource(): Boolean =
    recordingBackdropSources.get()?.isNotEmpty() == true

private fun isRecordingBackdropSource(layerName: String): Boolean =
    recordingBackdropSources.get()?.contains(layerName) == true

private inline fun <T> drawingBackdropSource(layerName: String, block: () -> T): T {
    val stack = drawingBackdropSources.get() ?: ArrayDeque<String>().also(drawingBackdropSources::set)
    stack.addLast(layerName)
    return try {
        block()
    } finally {
        stack.removeLast()
    }
}

private fun isDrawingBackdropSource(layerName: String): Boolean =
    drawingBackdropSources.get()?.contains(layerName) == true

private fun isDrawingAnyBackdropSource(): Boolean =
    drawingBackdropSources.get()?.isNotEmpty() == true

// =============================================================================
// PUBLIC MODIFIERS
// =============================================================================

/**
 * Marks this composable subtree as a named backdrop source.
 *
 * The rendered pixels from this subtree are recorded and made available to
 * [layeredBackdropCapture] nodes that use the same [layerName] from higher/overlay
 * content. Source names are arbitrary, but stable constants are recommended for
 * production layouts.
 *
 * Important: do not put a [layeredBackdropCapture] inside this same source subtree and
 * point it back at [layerName]. A capture should sample a source behind it, not the
 * source that currently contains it. Same-layer sampling can feed the component back
 * into its own capture and is not supported as live glass.
 */
fun Modifier.layeredBackdropSource(layerName: String): Modifier =
    this.then(BackdropSourceElement(layerName))

/**
 * Applies a backdrop blur/glass effect using the most recent capture from [layerName].
 *
 * The modifier clips to [shape] when provided, otherwise it clips to [BackdropFilter.shape].
 * API 33+ Glass also uses the same resolved shape to drive rounded-edge refraction and
 * rim lighting. Use this on foreground or overlay UI that sits above the source it samples.
 *
 * Important: [layerName] must refer to a source layer behind this component, not the
 * source layer that contains this component. For example, a card inside foreground
 * should sample background; a modal in overlay may sample foreground/background. A modal
 * inside foreground sampling foreground is a feedback loop and is intentionally not a
 * supported topology, especially on API 33+ where live GPU capture is used.
 *
 * @param layerName Name passed to [layeredBackdropSource] for the backdrop to sample.
 * @param shape Optional clip shape override. If omitted, [BackdropFilter.shape] is used.
 * @param padding Extra sampled area around the clipped bounds. Useful when the blur needs
 * pixels just outside the visible surface.
 * @param filter Blur or glass filter to apply to the sampled backdrop.
 * @param autoInvalidateOnMove When true, movement requests a new source capture only
 * if the moved region cannot be satisfied by the current source capture. Normal movement
 * reuses the existing full-source capture and updates the crop locally.
 */
fun Modifier.layeredBackdropCapture(
    layerName: String,
    shape: Shape? = null,
    padding: PaddingValues = PaddingValues(0.dp),
    filter: BackdropFilter = BackdropFilter.Blur(),
    autoInvalidateOnMove: Boolean = true
): Modifier {
    val captureShape = shape ?: filter.shape
    return this
        .padding(padding)
        .clip(captureShape)
        .then(BackdropCaptureElement(layerName, captureShape, filter, autoInvalidateOnMove))
}

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

/**
 * Records the source subtree into ping-pong [GraphicsLayer]s (hardware path) or a fresh
 * [Picture] per capture (legacy software path).
 *
 * Ping-pong layers: the master layer referenced by current capture results is never the
 * layer being recorded into, so no layer is mutated or released while the RenderThread
 * may still draw a frame referencing it. Both layers are owned by this node and released
 * (frame-deferred) only on detach/reset.
 *
 * A fresh [Picture] per software capture avoids re-recording a Picture instance that a
 * cancelled background job may still be rasterizing.
 */
private class BackdropSourceNode(
    var layerName: String
) : Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode,
    DrawModifierNode,
    ObserverModifierNode {

    private var cachedState: BackdropState? = null
    private var captureLayerA: GraphicsLayer? = null
    private var captureLayerB: GraphicsLayer? = null
    private var recordIntoA = true

    fun updateLayerName(newName: String) {
        if (layerName != newName) {
            cachedState?.clearSourceCapture()
            layerName = newName
            cachedState = null
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val state = cachedState
            ?: currentValueOf(LocalBackdropLayerManager)?.getState(layerName).also { cachedState = it }
        state?.updateSourceRect(Rect(coordinates.positionInRoot(), coordinates.size.toSize()))
    }

    override fun onDetach() {
        cachedState?.clearSourceCapture()
        releaseCaptureLayers()
        cachedState = null
    }

    override fun onReset() {
        cachedState?.clearSourceCapture()
        releaseCaptureLayers()
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
                    val outerScope = this
                    drawingBackdropSource(layerName) {
                        drawContent()
                    }

                    val layer = nextCaptureLayer()
                    val captureSize = state.scaledSize(w, h)
                    layer.record(size = captureSize) {
                        scale(
                            scaleX = state.captureScaleFactor,
                            scaleY = state.captureScaleFactor,
                            pivot = Offset.Zero
                        ) {
                            recordingBackdropSource(layerName) {
                                outerScope.drawContent()
                            }
                        }
                    }
                    backdropCaptureBackend.onHardwareLayerRecorded(
                        state = state,
                        layer = layer,
                        captureSize = captureSize
                    )
                } else {
                    drawingBackdropSource(layerName) {
                        drawContent()
                    }
                    val picture = Picture()
                    val canvas = picture.beginRecording(w, h)
                    val outerScope = this
                    draw(outerScope, layoutDirection, Canvas(canvas), size) {
                        recordingBackdropSource(layerName) {
                            outerScope.drawContent()
                        }
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

    private fun nextCaptureLayer(): GraphicsLayer {
        val graphicsContext = requireGraphicsContext()
        val layer = if (recordIntoA) {
            captureLayerA ?: graphicsContext.createGraphicsLayer().also { captureLayerA = it }
        } else {
            captureLayerB ?: graphicsContext.createGraphicsLayer().also { captureLayerB = it }
        }
        recordIntoA = !recordIntoA
        return layer
    }

    private fun releaseCaptureLayers() {
        val layers = listOfNotNull(captureLayerA, captureLayerB)
        captureLayerA = null
        captureLayerB = null
        recordIntoA = true
        if (layers.isEmpty()) return
        val graphicsContext = requireGraphicsContext()
        val state = cachedState
        if (state != null) {
            state.releaseLayersAfterFrames(layers) { graphicsContext.releaseGraphicsLayer(it) }
        } else {
            layers.forEach { graphicsContext.releaseGraphicsLayer(it) }
        }
    }
}

// =============================================================================
// CAPTURE NODE
// =============================================================================

private data class BackdropCaptureElement(
    val layerName: String,
    val shape: Shape,
    val filter: BackdropFilter,
    val autoInvalidateOnMove: Boolean
) : ModifierNodeElement<BackdropCaptureNode>() {
    override fun create() = BackdropCaptureNode(layerName, shape, filter, autoInvalidateOnMove)
    override fun update(node: BackdropCaptureNode) {
        node.update(layerName, shape, filter, autoInvalidateOnMove)
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "layeredBackdropCapture"
        properties["layerName"] = layerName
        properties["shape"] = shape
        properties["filter"] = filter
    }
}

private class BackdropCaptureNode(
    var layerName: String,
    var shape: Shape,
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
    private var recordingSnapshot: ImageBitmap? = null
    private var recordingSnapshotJob: Job? = null
    private var recordingSnapshotResult: BackdropState.CaptureResult? = null
    private var recordingSnapshotFilter: BackdropFilter? = null
    private var recordingSnapshotSize = IntSize.Zero
    private val drawCache = CaptureDrawCache()

    fun update(newName: String, newShape: Shape, newFilter: BackdropFilter, newAutoMove: Boolean) {
        val layerChanged = layerName != newName
        val shapeChanged = shape != newShape
        val filterChanged = filter != newFilter
        val shouldInvalidate = layerChanged || shapeChanged || filterChanged || autoInvalidateOnMove != newAutoMove
        if (layerChanged) {
            cachedState?.unregisterRegion(regionId)
            layerName = newName
            cachedState = null
            lastResult = null
            clearRecordingSnapshot()
            drawCache.clear()
        }
        if (shapeChanged || filterChanged) clearRecordingSnapshot()
        shape = newShape
        filter = newFilter
        autoInvalidateOnMove = newAutoMove
        if (filterChanged && !layerChanged) {
            cachedState?.updateRegionBlurRadius(regionId, currentCpuBlurRadiusPx())
        }
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
        state?.registerRegion(regionId, rect, currentCpuBlurRadiusPx())

        if (autoInvalidateOnMove) {
            val pos = coordinates.positionInWindow()
            if (lastPosition != Offset.Unspecified && lastPosition != pos) {
                if (state?.getResult(regionId) == null) {
                    state?.requestCapture()
                }
            }
            lastPosition = pos
        }
    }

    override fun ContentDrawScope.draw() {
        if (isRecordingBackdropSource(layerName) || isDrawingBackdropSource(layerName)) {
            drawContent()
            return
        }

        observeReads {
            val state = cachedState
            state?.structureInvalidator
            lastResult = state?.getResult(regionId)
        }

        val result = lastResult

        if (result == null) {
            cachedState?.requestCaptureIfMissing()
            drawCache.invalidateRecording()
            drawCache.cpuBlur.clear()
        }

        if (isRecordingAnyBackdropSource() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            recordingSnapshot?.let { snapshot ->
                drawImage(
                    image = snapshot,
                    dstSize = IntSize(
                        size.width.roundToInt().coerceAtLeast(1),
                        size.height.roundToInt().coerceAtLeast(1)
                    )
                )
            }
            drawContent()
            return
        }

        backdropCaptureBackend.run {
            drawCapture(
                filter = filter,
                shape = shape,
                result = result,
                layer = graphicsLayer,
                density = density,
                drawCache = drawCache
            )
        }
        if (isDrawingAnyBackdropSource()) {
            updateRecordingSnapshot(result)
        }
        drawContent()
    }

    override fun onReset() {
        cachedState?.unregisterRegion(regionId)
        cachedState = null
        lastPosition = Offset.Unspecified
        lastResult = null
        clearRecordingSnapshot()
        drawCache.clear()
    }

    override fun onDetach() {
        cachedState?.unregisterRegion(regionId)
        graphicsLayer?.let { layer ->
            val graphicsContext = requireGraphicsContext()
            val state = cachedState
            if (state != null) {
                state.releaseLayersAfterFrames(listOf(layer)) { graphicsContext.releaseGraphicsLayer(it) }
            } else {
                graphicsContext.releaseGraphicsLayer(layer)
            }
        }
        graphicsLayer = null
        clearRecordingSnapshot()
        drawCache.clear()
        cachedState = null
    }

    /**
     * Blur radius the legacy backend should pre-apply to this region's fallback crop.
     * Always zero on GPU backends so radius changes never trigger spurious recaptures.
     */
    private fun currentCpuBlurRadiusPx(): Int {
        if (!backdropCaptureBackend.createsFallbackBitmap) return 0
        val density = currentValueOf(LocalDensity).density
        return (filter.blurRadiusIntensity * 2f * density).roundToInt()
    }

    private fun updateRecordingSnapshot(result: BackdropState.CaptureResult?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        result ?: return
        val layer = graphicsLayer ?: return
        val snapshotSize = IntSize(
            layer.size.width.coerceAtLeast(1),
            layer.size.height.coerceAtLeast(1)
        )
        if (
            recordingSnapshotResult === result &&
            recordingSnapshotFilter == filter &&
            recordingSnapshotSize == snapshotSize &&
            recordingSnapshot != null
        ) {
            return
        }
        if (recordingSnapshotJob?.isActive == true) return

        val snapshotResult = result
        val snapshotFilter = filter
        recordingSnapshotJob = coroutineScope.launch(Dispatchers.Main) {
            val snapshot = runCatching { layer.toImageBitmap() }.getOrNull() ?: return@launch
            val oldSnapshot = recordingSnapshot
            recordingSnapshot = snapshot
            recordingSnapshotResult = snapshotResult
            recordingSnapshotFilter = snapshotFilter
            recordingSnapshotSize = snapshotSize
            oldSnapshot?.safeRecycle()
            invalidateDraw()
        }
    }

    private fun clearRecordingSnapshot() {
        recordingSnapshotJob?.cancel()
        recordingSnapshotJob = null
        recordingSnapshot?.safeRecycle()
        recordingSnapshot = null
        recordingSnapshotResult = null
        recordingSnapshotFilter = null
        recordingSnapshotSize = IntSize.Zero
    }
}

// =============================================================================
// FILTER DEFINITIONS
// =============================================================================

internal data class GlassCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
) {
    companion object {
        val Zero = GlassCornerRadii(0f, 0f, 0f, 0f)
    }
}

internal fun Shape.resolveGlassCornerRadii(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density
): GlassCornerRadii {
    return when (val outline = createOutline(size, layoutDirection, density)) {
        is Outline.Rounded -> {
            val roundRect = outline.roundRect
            GlassCornerRadii(
                topLeft = min(roundRect.topLeftCornerRadius.x, roundRect.topLeftCornerRadius.y),
                topRight = min(roundRect.topRightCornerRadius.x, roundRect.topRightCornerRadius.y),
                bottomRight = min(roundRect.bottomRightCornerRadius.x, roundRect.bottomRightCornerRadius.y),
                bottomLeft = min(roundRect.bottomLeftCornerRadius.x, roundRect.bottomLeftCornerRadius.y)
            )
        }
        else -> GlassCornerRadii.Zero
    }
}

/**
 * Immutable backdrop filter specification.
 *
 * Filters carry no mutable shader/effect state, so a single instance can be hoisted and
 * shared across any number of capture nodes; per-node GPU state lives in
 * [CaptureDrawCache], which eliminates uniform races between nodes sharing one filter.
 */
@Stable
sealed interface BackdropFilter {
    val shape: Shape
    val blurRadiusIntensity: Float

    @Stable
    data class Blur(
        @field:FloatRange(0.0, 10.0) override val blurRadiusIntensity: Float = 5f,
        val tint: Color = Color.Transparent,
        override val shape: Shape = RoundedCornerShape(12.dp),
    ) : BackdropFilter

    @Stable
    data class Glass(
        @field:FloatRange(0.0, 10.0) override val blurRadiusIntensity: Float = 3f,
        val refraction: Float = 0.24f,
        val dispersion: Float = 0.2f,
        val edge: Float = 0.18f,
        val tint: Color = Color.Transparent,
        override val shape: Shape = RoundedCornerShape(12.dp),
    ) : BackdropFilter
}

// =============================================================================
// PER-NODE DRAW CACHE
// =============================================================================

/**
 * Per-capture-node caches for everything the draw path would otherwise recompute or
 * share unsafely:
 *
 * - GPU shader/effect state. Owning the [RuntimeShader] per node guarantees two nodes
 *   reusing one [BackdropFilter] value never race on the same shader uniforms.
 * - Resolved [GlassCornerRadii], keyed by shape/size/direction/density, avoiding an
 *   [Outline] allocation per Glass frame.
 * - The last recorded capture result + size, letting the API 33 backend skip layer
 *   re-recording entirely when nothing changed.
 * - The last applied [androidx.compose.ui.graphics.RenderEffect], avoiding redundant
 *   RenderNode property writes.
 * - The legacy [CpuBlurCache].
 */
internal class CaptureDrawCache {
    val cpuBlur = CpuBlurCache()

    private var glassShader: RuntimeShader? = null
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null
    private var cachedEffectIsGlass = false
    private var cachedEffectBlurPx = -1f

    private var uniformGlass: BackdropFilter.Glass? = null
    private var uniformCornerRadii: GlassCornerRadii? = null
    private var uniformW = -1f
    private var uniformH = -1f

    private var radiiShape: Shape? = null
    private var radiiSize = Size.Unspecified
    private var radiiLayoutDirection: LayoutDirection? = null
    private var radiiDensity = -1f
    private var cachedRadii = GlassCornerRadii.Zero

    private var recordedResult: BackdropState.CaptureResult? = null
    private var recordedSize = IntSize.Zero

    private var appliedEffect: androidx.compose.ui.graphics.RenderEffect? = null
    private var effectApplied = false

    fun shouldRecord(result: BackdropState.CaptureResult, targetSize: IntSize): Boolean =
        recordedResult !== result || recordedSize != targetSize

    fun markRecorded(result: BackdropState.CaptureResult, targetSize: IntSize) {
        recordedResult = result
        recordedSize = targetSize
    }

    fun invalidateRecording() {
        recordedResult = null
    }

    fun applyRenderEffect(layer: GraphicsLayer, effect: androidx.compose.ui.graphics.RenderEffect?) {
        if (!effectApplied || appliedEffect !== effect) {
            layer.renderEffect = effect
            appliedEffect = effect
            effectApplied = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun blurRenderEffect(blurPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (blurPx <= 0f) return null
        if (!cachedEffectIsGlass && cachedEffectBlurPx == blurPx) {
            cachedEffect?.let { return it }
        }
        val effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
        cachedEffect = effect
        cachedEffectIsGlass = false
        cachedEffectBlurPx = blurPx
        return effect
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun glassRenderEffect(blurPx: Float): androidx.compose.ui.graphics.RenderEffect {
        if (cachedEffectIsGlass && cachedEffectBlurPx == blurPx) {
            cachedEffect?.let { return it }
        }
        val glassEffect = RenderEffect.createRuntimeShaderEffect(requireGlassShader(), "content")
        val effect = if (blurPx > 0f) {
            RenderEffect.createChainEffect(
                glassEffect,
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            ).asComposeRenderEffect()
        } else {
            glassEffect.asComposeRenderEffect()
        }
        cachedEffect = effect
        cachedEffectIsGlass = true
        cachedEffectBlurPx = blurPx
        return effect
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyGlassUniforms(
        glass: BackdropFilter.Glass,
        w: Float,
        h: Float,
        cornerRadii: GlassCornerRadii
    ) {
        val shader = requireGlassShader()
        if (uniformGlass != glass || uniformCornerRadii != cornerRadii) {
            shader.setFloatUniform(
                "cornerRadii",
                cornerRadii.topLeft,
                cornerRadii.topRight,
                cornerRadii.bottomRight,
                cornerRadii.bottomLeft
            )
            shader.setFloatUniform("refraction", glass.refraction)
            shader.setFloatUniform("dispersion", glass.dispersion)
            shader.setFloatUniform("edge", glass.edge)
            shader.setFloatUniform("tint", glass.tint.red, glass.tint.green, glass.tint.blue, glass.tint.alpha)
            uniformGlass = glass
            uniformCornerRadii = cornerRadii
        }
        if (uniformW != w || uniformH != h) {
            shader.setFloatUniform("resolution", w, h)
            shader.setFloatUniform("lensCenter", w / 2f, h / 2f)
            shader.setFloatUniform("lensSize", w, h)
            uniformW = w
            uniformH = h
        }
    }

    fun cornerRadii(
        shape: Shape,
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): GlassCornerRadii {
        if (
            shape == radiiShape &&
            size == radiiSize &&
            layoutDirection == radiiLayoutDirection &&
            density.density == radiiDensity
        ) {
            return cachedRadii
        }
        cachedRadii = shape.resolveGlassCornerRadii(size, layoutDirection, density)
        radiiShape = shape
        radiiSize = size
        radiiLayoutDirection = layoutDirection
        radiiDensity = density.density
        return cachedRadii
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requireGlassShader(): RuntimeShader =
        glassShader ?: RuntimeShader(GLASS_SHADER).also { glassShader = it }

    fun clear() {
        cpuBlur.clear()
        recordedResult = null
        recordedSize = IntSize.Zero
        appliedEffect = null
        effectApplied = false
    }
}

// =============================================================================
// STATE
// =============================================================================

/**
 * Capture state for one named source layer.
 *
 * Regions live in a plain map; each region publishes its [CaptureResult] through its own
 * snapshot state, so movement of one capture node invalidates only that node and is
 * invisible to the source's draw. [structureInvalidator] is bumped only on structural
 * changes (new region registered, source cleared) so a node that drew before its region
 * existed gets one catch-up invalidation.
 *
 * Master [GraphicsLayer]s are owned by the source node (ping-pong) and are never
 * released here; this state only holds a reference to the currently active one.
 *
 * All timing uses [SystemClock.uptimeMillis] so wall-clock jumps cannot stall or storm
 * captures. All region/master mutation happens on the main thread; background jobs only
 * produce bitmaps and hop back to main to publish.
 */
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
        val fallbackBlurRadiusPx: Int = 0,
        val captureScaleFactor: Float
    )

    private class Region(var rect: Rect, var cpuBlurRadiusPx: Int) {
        val result = mutableStateOf<CaptureResult?>(null, neverEqualPolicy())
    }

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

    private val regions = HashMap<Int, Region>()

    internal var sourceInvalidator by mutableLongStateOf(0L)
        private set

    internal var structureInvalidator by mutableLongStateOf(0L)
        private set

    private var masterImage: ImageBitmap? = null
    private var masterLayer: GraphicsLayer? = null
    private var blurredMasters: Map<Int, ImageBitmap> = emptyMap()
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

    /** Requests a capture only when no master exists and none is pending or processing. */
    fun requestCaptureIfMissing() {
        if (!hasMasterCapture && !captureRequested && !isProcessing) {
            requestCapture()
        }
    }

    val shouldCapture: Boolean
        get() = (regions.isNotEmpty() || captureRequested)
                && processingJob?.isActive != true
                && debounced()
                && isUpdateEnabled()

    fun getResult(id: Int): CaptureResult? = regions[id]?.result?.value

    fun requestCapture() {
        requestCapture(force = false)
    }

    internal fun requestCapture(force: Boolean) {
        if (regions.isEmpty() && !force) return
        captureRequested = true
        if (isProcessing) return
        invalidateOrSchedule()
    }

    internal fun requestCaptureAfterPendingWork() {
        if (regions.isEmpty() || !isUpdateEnabled() || (debounced() && !isProcessing)) return
        requestCapture()
    }

    internal fun registerRegion(id: Int, rect: Rect, cpuBlurRadiusPx: Int) {
        val existing = regions[id]
        if (existing != null && existing.rect == rect && existing.cpuBlurRadiusPx == cpuBlurRadiusPx) return

        val isNew = existing == null
        val radiusChanged = existing != null && existing.cpuBlurRadiusPx != cpuBlurRadiusPx
        val region = existing ?: Region(rect, cpuBlurRadiusPx).also { regions[id] = it }
        region.rect = rect
        region.cpuBlurRadiusPx = cpuBlurRadiusPx

        val oldResult = region.result.value
        val geometry = if (hasMasterCapture) {
            cropGeometryForRegion(rect, masterSourceRect, masterScaledW, masterScaledH)
        } else {
            null
        }
        val newResult = when {
            geometry == null -> null
            oldResult != null && oldResult.matches(masterImage, masterLayer, geometry) -> oldResult
            else -> captureResultForGeometry(masterImage, masterLayer, geometry, region.cpuBlurRadiusPx)
        }
        if (newResult !== oldResult) {
            oldResult?.fallbackBitmap?.safeRecycle()
            region.result.value = newResult
        }
        if (isNew) {
            structureInvalidator++
            requestCapture()
        } else if (radiusChanged) {
            requestCapture()
        }
    }

    internal fun updateRegionBlurRadius(id: Int, radiusPx: Int) {
        val region = regions[id] ?: return
        if (region.cpuBlurRadiusPx == radiusPx) return
        region.cpuBlurRadiusPx = radiusPx
        requestCapture()
    }

    internal fun unregisterRegion(id: Int) {
        regions.remove(id)?.result?.value?.fallbackBitmap?.safeRecycle()
    }

    internal fun updateSourceRect(rect: Rect) {
        if (sourceRect == rect) return
        sourceRect = rect
        requestCapture()
    }

    /** Distinct nonzero blur radii currently requested by regions (legacy backend only). */
    internal fun pendingCpuBlurRadii(): Set<Int> {
        if (!backdropCaptureBackend.createsFallbackBitmap) return emptySet()
        val radii = HashSet<Int>()
        regions.values.forEach { region ->
            if (region.cpuBlurRadiusPx > 0) radii.add(region.cpuBlurRadiusPx)
        }
        return radii
    }

    /**
     * Releases [layers] after two rendered frames so any in-flight RenderThread frame
     * that still references them has been consumed. Falls back to immediate release if
     * the owning scope is already cancelled (composition gone, nothing rendering).
     */
    internal fun releaseLayersAfterFrames(layers: List<GraphicsLayer>, release: (GraphicsLayer) -> Unit) {
        if (layers.isEmpty()) return
        val job = scope.launch {
            repeat(2) { withFrameNanos { } }
            layers.forEach { runCatching { release(it) } }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                layers.forEach { runCatching { release(it) } }
            }
        }
    }

    internal fun dispose() {
        processingJob?.cancel()
        scheduledJob?.cancel()
        regions.values.forEach { it.result.value?.fallbackBitmap?.safeRecycle() }
        regions.clear()
        reusableBitmap?.safeRecycle()
        reusableBitmap = null
        masterImage?.safeRecycle()
        masterImage = null
        masterLayer = null
        blurredMasters.values.forEach { it.safeRecycle() }
        blurredMasters = emptyMap()
    }

    internal fun onPictureRecorded(picture: Picture, width: Int, height: Int) {
        beginCapture()

        val scaledW = (width * scaleFactor).roundToInt().coerceAtLeast(1)
        val scaledH = (height * scaleFactor).roundToInt().coerceAtLeast(1)
        val currentSource = sourceRect
        val blurRadii = pendingCpuBlurRadii()

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
            var newBlurredMasters: Map<Int, ImageBitmap> = emptyMap()
            var applied = false
            try {
                android.graphics.Canvas(master).apply {
                    scale(scaleFactor, scaleFactor)
                    drawPicture(picture)
                }
                if (!isActive) { master.safeRecycle(); return@launch }

                newBlurredMasters = createBlurredMasters(master, blurRadii)
                val masterImg = master.asImageBitmap()

                withContext(Dispatchers.Main) {
                    applyMasterImage(
                        masterImg = masterImg,
                        newBlurredMasters = newBlurredMasters,
                        currentSource = currentSource,
                        scaledW = scaledW,
                        scaledH = scaledH,
                        reuseOldMaster = true
                    )
                    applied = true
                }
            } catch (e: CancellationException) {
                if (!applied) {
                    master.safeRecycle()
                    newBlurredMasters.values.forEach { it.safeRecycle() }
                }
                throw e
            } catch (e: Exception) {
                master.safeRecycle()
                newBlurredMasters.values.forEach { it.safeRecycle() }
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

    internal fun clearSourceCapture() {
        scheduledJob?.cancel()
        scheduledJob = null
        processingJob?.cancel()
        processingJob = null
        captureRequested = false

        regions.values.forEach { region ->
            val result = region.result.value
            result?.fallbackBitmap?.safeRecycle()
            if (result != null) region.result.value = null
        }

        val oldMasterImage = masterImage
        val oldBlurred = blurredMasters
        masterImage = null
        masterLayer = null
        blurredMasters = emptyMap()
        masterScaledW = 0
        masterScaledH = 0
        masterSourceRect = Rect.Zero
        reusableBitmap?.safeRecycle()
        reusableBitmap = null
        oldMasterImage?.safeRecycle()
        oldBlurred.values.forEach { it.safeRecycle() }

        structureInvalidator++
    }

    internal fun applyHardwareImageCapture(
        captured: ImageBitmap,
        newBlurredMasters: Map<Int, ImageBitmap>,
        session: HardwareCaptureSession
    ) {
        applyMasterImage(
            masterImg = captured,
            newBlurredMasters = newBlurredMasters,
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
        newBlurredMasters: Map<Int, ImageBitmap>,
        currentSource: Rect,
        scaledW: Int,
        scaledH: Int,
        reuseOldMaster: Boolean
    ) {
        val oldMaster = masterImage
        val oldBlurred = blurredMasters
        masterImage = masterImg
        masterLayer = null
        blurredMasters = newBlurredMasters
        masterSourceRect = currentSource
        masterScaledW = scaledW
        masterScaledH = scaledH

        regions.values.forEach { region ->
            val newResult = cropForRegion(region, currentSource, masterImg, null, scaledW, scaledH)
            region.result.value?.fallbackBitmap?.safeRecycle()
            region.result.value = newResult
        }

        recycleOrReuseOldMaster(oldMaster, reuseOldMaster)
        oldBlurred.values.forEach { it.safeRecycle() }
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
        val oldBlurred = blurredMasters
        masterImage = null
        masterLayer = layer
        blurredMasters = emptyMap()
        masterSourceRect = currentSource
        masterScaledW = scaledW
        masterScaledH = scaledH

        regions.values.forEach { region ->
            val newResult = cropForRegion(region, currentSource, null, layer, scaledW, scaledH)
            region.result.value?.fallbackBitmap?.safeRecycle()
            region.result.value = newResult
        }

        recycleOrReuseOldMaster(oldMaster, reuseOldMaster = false)
        oldBlurred.values.forEach { it.safeRecycle() }
        processingJob = null

        if (captureRequested) invalidateOrSchedule()
    }

    private fun beginCapture() {
        captureRequested = false
        lastCaptureTime = SystemClock.uptimeMillis()
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
        (SystemClock.uptimeMillis() - lastCaptureTime) >= debounceMs

    private fun invalidateOrSchedule() {
        if (debounced()) {
            sourceInvalidator++
        } else if (scheduledJob?.isActive != true) {
            scheduledJob = scope.launch {
                val remaining = (debounceMs - (SystemClock.uptimeMillis() - lastCaptureTime)).coerceAtLeast(0L)
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
        val drawOffset = (intersection.topLeft - regionRect.topLeft) + Offset(errorX, errorY)
        val drawSize = IntSize((w / scaleFactor).roundToInt(), (h / scaleFactor).roundToInt())

        return CropGeometry(IntOffset(l, t), IntSize(w, h), drawOffset, drawSize)
    }

    private fun cropForRegion(
        region: Region,
        source: Rect,
        masterImage: ImageBitmap?,
        masterLayer: GraphicsLayer?,
        scaledW: Int,
        scaledH: Int
    ): CaptureResult? {
        val geometry = cropGeometryForRegion(region.rect, source, scaledW, scaledH) ?: return null
        return captureResultForGeometry(masterImage, masterLayer, geometry, region.cpuBlurRadiusPx)
    }

    private fun captureResultForGeometry(
        masterImage: ImageBitmap?,
        masterLayer: GraphicsLayer?,
        geometry: CropGeometry,
        regionBlurRadiusPx: Int
    ): CaptureResult {
        var fallbackBlurRadiusPx = 0
        val fallback = if (backdropCaptureBackend.createsFallbackBitmap && masterImage != null) {
            val cropSource = blurredMasters[regionBlurRadiusPx]
                ?.also { fallbackBlurRadiusPx = regionBlurRadiusPx }
                ?: masterImage
            cropBitmap(
                cropSource,
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
            fallbackBlurRadiusPx = fallbackBlurRadiusPx,
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

/**
 * Per-node cache for residual draw-time CPU blur and glass refraction on the legacy
 * path. Steady-state blur is pre-applied to master crops at capture time; [blur] only
 * runs for the transitional frame(s) after a radius change, and [glassRefraction] for
 * shape-dependent refraction that cannot be shared across regions.
 */
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