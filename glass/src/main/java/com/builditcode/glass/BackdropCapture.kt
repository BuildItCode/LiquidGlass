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
 * - Replaying a subtree into an upper source reuses lower captures. It neither starts
 *   another lower capture nor schedules a refresh just because the subtree was replayed.
 *
 * GPU resources:
 * - Each source owns two ping-pong [GraphicsLayer]s and alternates recordings between
 *   them behind a stable published layer. Updating that layer's display list uses normal
 *   RenderNode synchronization and propagates to retained consumers in the same GPU frame.
 *   Layers are released on node detach/reset, deferred by two frames via
 *   [BackdropState.releaseLayersAfterFrames].
 * - All shader/effect state is per capture node ([CaptureDrawCache]); [BackdropFilter]
 *   values are pure immutable specs, so sharing one filter instance across nodes is safe.
 *
 * Legacy (< API 33) path:
 * - The scaled master bitmap is stack-blurred once per distinct region blur radius on
 *   [Dispatchers.Default] at capture time. Region crops and glass refraction are also
 *   prepared there, so steady-state draws only draw prepared bitmaps. If a
 *   filter's blur radius changes, a recapture is requested and the previous blur level
 *   is shown until it lands.
 * - Because legacy masters are snapshots (unlike API 33+ layer masters, which reference
 *   the live RenderNode tree), [BackdropState] keeps a debounce-cadence recapture loop
 *   alive while regions intersect the source so backgrounds moving behind static glass stay current.
 * - Recorded upper sources retain any lower software bitmaps they draw. Recycling waits
 *   for pending Picture jobs, hardware readbacks, and retained source layers to release them.
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
import androidx.annotation.FloatRange
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.Snapshot
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toColorLong
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

// =============================================================================
// BITMAP LIFECYCLE
// =============================================================================

internal fun Bitmap.safeRecycle() {
    BackdropBitmapReferences.recycle(this)
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
internal suspend fun createBlurredMasters(
    master: Bitmap,
    radii: Set<Int>,
    workspace: StackBlurWorkspace
): Map<Int, ImageBitmap> {
    if (radii.isEmpty()) return emptyMap()
    val out = HashMap<Int, ImageBitmap>(radii.size)
    try {
        for (radius in radii) {
            currentCoroutineContext().ensureActive()
            var copy: Bitmap? = null
            try {
                val bitmap = master.copy(Bitmap.Config.ARGB_8888, true) ?: continue
                copy = bitmap
                applyStackBlur(bitmap, radius, workspace)
                out[radius] = bitmap.asImageBitmap()
                copy = null // Ownership passes to the returned map.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the other blur levels when this copy cannot be prepared.
            } finally {
                copy?.safeRecycle()
            }
        }
        currentCoroutineContext().ensureActive()
        return out
    } catch (error: Throwable) {
        out.values.forEach { it.safeRecycle() }
        throw error
    }
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
 * @param defaultDebounceMs Minimum interval between CPU/bitmap snapshots for new layer states. Live GPU captures follow source draws.
 * @param disableHardwareAcceleration When true, source capture stays on the software
 * picture/bitmap path and legacy hardware snapshot promotion is disabled.
 */
@Composable
fun rememberBackdropManager(
    defaultScaleFactor: Float = 0.4f,
    defaultDebounceMs: Long = 16L,
    disableHardwareAcceleration: Boolean = false
): BackdropLayerManager {
    val scope = rememberCoroutineScope()
    // Fall back to software capture when the caller opts out, or when this device/window is not
    // hardware accelerated (the hardware backend needs a hardware-accelerated canvas to run its
    // RenderEffect blur/glass). The API-level gate lives in [BackdropState.backend].
    val hardwareAccelerated = LocalView.current.isHardwareAccelerated
    val forceSoftware = disableHardwareAcceleration || !hardwareAccelerated
    val manager = remember(scope, defaultScaleFactor, defaultDebounceMs, forceSoftware) {
        BackdropLayerManager(
            scope = scope,
            defaultScaleFactor = defaultScaleFactor,
            defaultDebounceMs = defaultDebounceMs,
            disableHardwareAcceleration = forceSoftware
        )
    }
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
    private val defaultDebounceMs: Long,
    private val disableHardwareAcceleration: Boolean = false
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
        if (!shouldUpdate) return
        shouldUpdate = false
        states.values.forEach { it.pauseUpdates() }
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
            BackdropState(
                scope = scope,
                scaleFactor = defaultScaleFactor,
                debounceMs = defaultDebounceMs,
                disableHardwareAcceleration = disableHardwareAcceleration,
                isUpdateEnabled = { shouldUpdate }
            )
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

/**
 * Capability-based default backend: hardware capture when the platform supports it (API 33+),
 * otherwise software. A specific [BackdropState] may still resolve to software via
 * [BackdropState.backend] when hardware acceleration is disabled for that backdrop. Used where
 * a backend is needed without a state in hand (e.g. configuring a capture node's layer on attach).
 */
internal val backdropCaptureBackend: BackdropCaptureBackend =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) HardwareBackdropCapture
    else SoftwareBackdropCapture

internal interface BackdropCaptureBackend {
    val createsFallbackBitmap: Boolean

    /**
     * True when masters are rasterized snapshots that go stale as source content changes
     * (legacy). [BackdropState] then keeps a debounce-cadence recapture loop alive while
     * regions intersect the source, since content animating inside its own child layer never
     * re-invalidates the source's draw. False when masters are live [GraphicsLayer]s
     * that reflect content changes without recapturing (API 33+).
     */
    val requiresContinuousCapture: Boolean

    fun usesHardwareLayerForSource(state: BackdropState): Boolean

    fun configureCaptureLayer(layer: GraphicsLayer)

    fun onHardwareLayerRecorded(
        state: BackdropState,
        layer: GraphicsLayer,
        captureSize: IntSize,
        bitmapLease: BackdropBitmapLease? = null
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

private val recordingBackdropSources = ThreadLocal<ArrayDeque<BackdropState>>()
private val drawingBackdropSources = ThreadLocal<ArrayDeque<BackdropState>>()

internal inline fun <T> recordingBackdropSource(state: BackdropState, block: () -> T): T =
    withBackdropSource(recordingBackdropSources, state, block)

internal fun isRecordingBackdropSource(state: BackdropState): Boolean =
    recordingBackdropSources.get()?.contains(state) == true

// An ancestor has already drawn this subtree normally before replaying it for capture.
internal fun isReplayingBackdropSource(): Boolean = recordingBackdropSources.get()?.isNotEmpty() == true

internal inline fun <T> drawingBackdropSource(state: BackdropState, block: () -> T): T =
    withBackdropSource(drawingBackdropSources, state, block)

internal fun isDrawingBackdropSource(state: BackdropState): Boolean =
    drawingBackdropSources.get()?.contains(state) == true

private inline fun <T> withBackdropSource(
    context: ThreadLocal<ArrayDeque<BackdropState>>,
    state: BackdropState,
    block: () -> T
): T {
    val stack = context.get() ?: ArrayDeque<BackdropState>().also(context::set)
    stack.addLast(state)
    return try {
        block()
    } finally {
        stack.removeLast()
    }
}

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
    // Isolate the source display list from unrelated parent/overlay draw invalidations.
    // Auto compositing retains commands without allocating a source-sized texture.
    this.graphicsLayer().then(BackdropSourceElement(layerName))

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
 * Ping-pong content layers sit behind one stable GPU publication layer, so consumers
 * retain a live reference across source recordings. RenderNode synchronizes display-list
 * changes with the RenderThread. All three layers are owned by this node and released
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
    private var publishedLayer: GraphicsLayer? = null
    private val captureBitmapLeases = HashMap<GraphicsLayer, BackdropBitmapLease>()
    private var recordIntoA = true
    private var lastSourceRect = Rect.Zero

    private fun bindState(): BackdropState? {
        val state = currentValueOf(LocalBackdropLayerManager)?.getState(layerName)
        if (state !== cachedState) {
            cachedState?.clearSourceCapture()
            releaseCaptureLayers()
            cachedState = state
            state?.updateSourceRect(lastSourceRect)
        }
        return state
    }

    fun updateLayerName(newName: String) {
        if (layerName != newName) {
            cachedState?.clearSourceCapture()
            releaseCaptureLayers()
            layerName = newName
            cachedState = null
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        lastSourceRect = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        bindState()?.updateSourceRect(lastSourceRect)
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
        var observedState: BackdropState? = null
        observeReads {
            observedState = bindState()
            observedState?.sourceInvalidator
        }
        val captureState = observedState

        if (captureState == null) {
            drawContent()
            return
        }
        val state = captureState

        val w = size.width.roundToInt()
        val h = size.height.roundToInt()

        if (w > 0 && h > 0 && state.shouldCapture) {
            try {
                if (state.backend.usesHardwareLayerForSource(state)) {
                    val outerScope = this
                    drawingBackdropSource(state) {
                        drawContent()
                    }

                    val layer = nextCaptureLayer()
                    val captureSize = state.scaledSize(w, h)
                    val lease = BackdropBitmapLease()
                    try {
                        layer.record(size = captureSize) {
                            scale(
                                scaleX = state.captureScaleFactor,
                                scaleY = state.captureScaleFactor,
                                pivot = Offset.Zero
                            ) {
                                recordingBackdropSource(state) {
                                    retainingBackdropBitmaps(lease) { outerScope.drawContent() }
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        lease.close()
                        throw error
                    }
                    captureBitmapLeases.put(layer, lease)?.let { old ->
                        if (old.isEmpty) old.close() else state.releaseAfterFrames { old.close() }
                    }
                    val output = if (!state.backend.createsFallbackBitmap) {
                        // Consumers retain this stable indirection. Updating its display list
                        // makes every level see the new source in the same RenderThread frame,
                        // without waiting for snapshot invalidation to travel up the chain.
                        val published = publishedLayer ?: requireGraphicsContext().createGraphicsLayer()
                            .also { publishedLayer = it }
                        published.record(size = captureSize) { drawLayer(layer) }
                        published
                    } else layer
                    state.backend.onHardwareLayerRecorded(
                        state = state,
                        layer = output,
                        captureSize = captureSize,
                        bitmapLease = lease
                    )
                } else {
                    drawingBackdropSource(state) {
                        drawContent()
                    }
                    val picture = Picture()
                    val lease = BackdropBitmapLease()
                    var transferred = false
                    try {
                        val canvas = picture.beginRecording(w, h)
                        val outerScope = this
                        try {
                            draw(outerScope, layoutDirection, Canvas(canvas), size) {
                                recordingBackdropSource(state) {
                                    retainingBackdropBitmaps(lease) { outerScope.drawContent() }
                                }
                            }
                        } finally {
                            picture.endRecording()
                        }
                        state.onPictureRecorded(picture, w, h, lease)
                        transferred = true
                    } finally {
                        if (!transferred) lease.close()
                    }
                }
            } catch (e: Exception) {
            }
        } else {
            state.requestCaptureAfterPendingWork()
            drawingBackdropSource(state) { drawContent() }
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
        val layers = listOfNotNull(publishedLayer, captureLayerA, captureLayerB)
        publishedLayer = null
        val bitmapLeases = captureBitmapLeases.toMap()
        captureBitmapLeases.clear()
        captureLayerA = null
        captureLayerB = null
        recordIntoA = true
        if (layers.isEmpty()) return
        val graphicsContext = requireGraphicsContext()
        val state = cachedState
        if (state != null) {
            state.releaseLayersAfterFrames(layers) {
                try { graphicsContext.releaseGraphicsLayer(it) } finally { bitmapLeases[it]?.close() }
            }
        } else {
            layers.forEach {
                try { graphicsContext.releaseGraphicsLayer(it) } finally { bitmapLeases[it]?.close() }
            }
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
    private var graphicsLayer: GraphicsLayer? = null
    private var lastResult: BackdropState.CaptureResult? = null
    private val drawCache = CaptureDrawCache()
    private var lastRegionRect: Rect? = null
    private var cpuGlassParameters = filter.cpuGlassParameters()

    private fun bindState(): BackdropState? {
        val state = currentValueOf(LocalBackdropLayerManager)?.getState(layerName)
        if (state !== cachedState) {
            cachedState?.unregisterRegion(regionId)
            cachedState = state
            lastResult = null
            drawCache.clear()
            lastRegionRect?.let {
                state?.registerRegion(regionId, it, currentCpuBlurRadiusPx(), currentCpuGlassParameters())
            }
        }
        return state
    }

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
            drawCache.clear()
        }
        shape = newShape
        filter = newFilter
        if (filterChanged) cpuGlassParameters = newFilter.cpuGlassParameters()
        autoInvalidateOnMove = newAutoMove
        if (filterChanged && !layerChanged) {
            cachedState?.updateRegionFilter(regionId, currentCpuBlurRadiusPx(), currentCpuGlassParameters())
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
        val rect = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        lastRegionRect = rect
        val state = bindState()
        state?.registerRegion(regionId, rect, currentCpuBlurRadiusPx(), currentCpuGlassParameters())

        if (autoInvalidateOnMove && state != null && state.getResult(regionId) == null) {
            state.requestCapture()
        }
    }

    override fun ContentDrawScope.draw() {
        var drawingOwnSource = false
        observeReads {
            val state = bindState()
            drawingOwnSource = state != null && (isRecordingBackdropSource(state) || isDrawingBackdropSource(state))
            if (!drawingOwnSource) {
                state?.updateRegionFilter(regionId, currentCpuBlurRadiusPx(), currentCpuGlassParameters())
                state?.structureInvalidator
                lastResult = state?.getResult(regionId)
            }
        }
        if (drawingOwnSource) {
            drawContent()
            return
        }

        val result = lastResult

        if (result == null) {
            cachedState?.requestCaptureIfMissing()
            drawCache.invalidateRecording()
            drawCache.cpuBlur.clear()
        }

        (cachedState?.backend ?: backdropCaptureBackend).run {
            drawCapture(
                filter = filter,
                shape = shape,
                result = result,
                layer = graphicsLayer,
                density = density,
                drawCache = drawCache
            )
        }
        drawContent()
    }

    override fun onReset() {
        cachedState?.unregisterRegion(regionId)
        cachedState = null
        lastRegionRect = null
        lastResult = null
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
        drawCache.clear()
        cachedState = null
        lastRegionRect = null
        lastResult = null
    }

    /**
     * Blur radius the legacy backend should pre-apply to this region's fallback crop.
     * Always zero on GPU backends so radius changes never trigger spurious recaptures.
     */
    private fun currentCpuBlurRadiusPx(): Int {
        if (!(cachedState?.backend ?: backdropCaptureBackend).createsFallbackBitmap) return 0
        val density = currentValueOf(LocalDensity).density
        return (filter.blurRadiusIntensity * 2f * density).roundToInt()
    }

    private fun currentCpuGlassParameters(): CpuGlassParameters? =
        cpuGlassParameters.takeIf { (cachedState?.backend ?: backdropCaptureBackend).createsFallbackBitmap }
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
    private var platformBlurEffect: RenderEffect? = null
    private var platformBlurRadius = -1f

    private var uniformGlass: BackdropFilter.Glass? = null
    private var uniformCornerRadii: GlassCornerRadii? = null
    private var uniformW = -1f
    private var uniformH = -1f

    private var radiiShape: Shape? = null
    private var radiiSize = Size.Unspecified
    private var radiiLayoutDirection: LayoutDirection? = null
    private var radiiDensity = -1f
    private var radiiFontScale = -1f
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
        val effect = platformBlur(blurPx).asComposeRenderEffect()
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
                platformBlur(blurPx)
            ).asComposeRenderEffect()
        } else {
            glassEffect.asComposeRenderEffect()
        }
        cachedEffect = effect
        cachedEffectIsGlass = true
        cachedEffectBlurPx = blurPx
        return effect
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun platformBlur(blurPx: Float): RenderEffect {
        if (platformBlurRadius == blurPx) platformBlurEffect?.let { return it }
        return RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP).also {
            platformBlurEffect = it
            platformBlurRadius = blurPx
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyGlassUniforms(
        glass: BackdropFilter.Glass,
        w: Float,
        h: Float,
        cornerRadii: GlassCornerRadii
    ) {
        val changed = uniformGlass?.refraction != glass.refraction ||
            uniformGlass?.dispersion != glass.dispersion || uniformGlass?.edge != glass.edge ||
            uniformGlass?.tint != glass.tint || uniformCornerRadii != cornerRadii ||
            uniformW != w || uniformH != h
        if (!changed) return

        val shader = requireGlassShader()
        val halfW = (w * 0.5f).coerceAtLeast(0.5f)
        val halfH = (h * 0.5f).coerceAtLeast(0.5f)
        val minExtent = min(halfW, halfH)
        val refraction = glass.refraction.coerceAtLeast(0f)
        val dispersion = glass.dispersion.coerceAtLeast(0f)
        val edge = glass.edge.coerceAtLeast(0f)
        val bevelWidth = (minExtent * refraction.coerceAtLeast(0.06f)).coerceIn(0.5f, minExtent)
        shader.setFloatUniform("lens", halfW, halfH, 1f / halfW, 1f / halfH)
        shader.setFloatUniform("cornerRadii", cornerRadii.topLeft, cornerRadii.topRight,
            cornerRadii.bottomRight, cornerRadii.bottomLeft)
        shader.setFloatUniform("optics", bevelWidth, 1f / bevelWidth,
            minExtent * refraction * 0.6f, minExtent * refraction * 0.12f)
        shader.setFloatUniform("dispersionPx", dispersion * minExtent * 0.1f)
        shader.setFloatUniform("edge", edge)
        shader.setFloatUniform("edgeWidth", min(bevelWidth, 1.5f + edge * 5f))
        shader.setColorUniform("tint", glass.tint.toColorLong())
        uniformGlass = glass
        uniformCornerRadii = cornerRadii
        uniformW = w
        uniformH = h
        // RenderEffect snapshots the shader builder's uniforms at creation time.
        if (cachedEffectIsGlass) cachedEffect = null
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
            density.density == radiiDensity && density.fontScale == radiiFontScale
        ) {
            return cachedRadii
        }
        cachedRadii = shape.resolveGlassCornerRadii(size, layoutDirection, density)
        radiiShape = shape
        radiiSize = size
        radiiLayoutDirection = layoutDirection
        radiiDensity = density.density
        radiiFontScale = density.fontScale
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
    private val disableHardwareAcceleration: Boolean = false,
    private val isUpdateEnabled: () -> Boolean = { true },
) {
    data class CaptureResult(
        val masterImage: ImageBitmap?,
        val masterLayer: GraphicsLayer?,
        val srcOffset: IntOffset,
        val srcSize: IntSize,
        val drawOffset: Offset,
        val sampleOffset: Offset,
        val drawSize: IntSize,
        val fallbackBitmap: ImageBitmap? = null,
        val fallbackBlurRadiusPx: Int = 0,
        val captureScaleFactor: Float
    ) {
        // Set before publication; keeping this out of the public constructor preserves its API.
        internal var preparedGlass: PreparedGlass? = null
    }

    private class Region(var rect: Rect, var cpuBlurRadiusPx: Int, var cpuGlass: CpuGlassParameters?) {
        val result = mutableStateOf<CaptureResult?>(null, neverEqualPolicy())
        // Bookkeeping can run inside a source draw. Only getResult() should subscribe
        // a drawing node to region updates; source publication must not observe them.
        var currentResult: CaptureResult?
            get() = Snapshot.withoutReadObservation { result.value }
            set(value) { result.value = value }
    }

    private data class CropGeometry(
        val srcOffset: IntOffset,
        val srcSize: IntSize,
        val drawOffset: Offset,
        val sampleOffset: Offset,
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
    private var activeRegionCount = 0

    internal var sourceInvalidator by mutableLongStateOf(0L)
        private set

    internal var structureInvalidator by mutableLongStateOf(0L)
        private set

    private var masterImage: ImageBitmap? = null
    private var masterLayer: GraphicsLayer? = null
    private var blurredMasters: Map<Int, ImageBitmap> = emptyMap()
    private var reusableBitmap: Bitmap? = null
    @Volatile
    private var blurWorkspace = StackBlurWorkspace()
    @Volatile
    private var glassWorkspace = CpuGlassWorkspace()
    private val blurMutex = Mutex()
    private var hardwarePictureReader: HardwarePictureReader? = null

    internal suspend fun prepareBlurredMasters(master: Bitmap, radii: Set<Int>): Map<Int, ImageBitmap> =
        blurMutex.withLock {
            // A cancelled worker may still be finishing a blur when its replacement starts.
            createBlurredMasters(master, radii, blurWorkspace)
        }

    internal fun snapshotSoftwareRegions(): List<SoftwareRegionRequest> =
        regions.mapNotNull { (id, region) ->
            if (intersectsSource(region.rect)) {
                SoftwareRegionRequest(id, region.rect, region.cpuBlurRadiusPx, region.cpuGlass)
            } else null
        }

    internal fun softwareCaptureReference(
        session: HardwareCaptureSession,
        requests: List<SoftwareRegionRequest>
    ): SoftwareCaptureReference? {
        val image = masterImage ?: return null
        if (masterSourceRect != session.sourceRect || masterScaledW != session.scaledW ||
            masterScaledH != session.scaledH ||
            requests.any { it.blurRadiusPx > 0 && !blurredMasters.containsKey(it.blurRadiusPx) }
        ) return null
        return SoftwareCaptureReference(image)
    }

    internal fun completeUnchangedSoftwareCapture(captured: ImageBitmap, reuse: Boolean) {
        // Region movement/filter updates already refresh their crop from the published
        // master. Identical pixels need neither another blur nor another GPU upload.
        recycleOrReuseOldMaster(captured, reuse)
        processingJob = null
        scheduleFollowUpCapture()
    }

    internal suspend fun prepareSoftwareCapture(
        master: ImageBitmap,
        requests: List<SoftwareRegionRequest>,
        session: HardwareCaptureSession
    ): PreparedSoftwareCapture = blurMutex.withLock {
        val prepared = PreparedSoftwareCapture(createBlurredMasters(
            master.asAndroidBitmap(),
            requests.mapNotNullTo(HashSet()) { it.blurRadiusPx.takeIf { radius -> radius > 0 } },
            blurWorkspace
        ))
        // A clear/detach can replace the workspace while this cancelled worker finishes.
        val workspace = glassWorkspace
        try {
            for (request in requests) {
                currentCoroutineContext().ensureActive()
                val geometry = cropGeometryForRegion(request.rect, session.sourceRect, session.scaledW, session.scaledH)
                    ?: continue
                val result = captureResultForGeometry(
                    master, null, geometry, request.blurRadiusPx, availableBlurredMasters = prepared.blurredMasters
                )
                prepared.regions[request.id] = result
                val crop = result.fallbackBitmap
                if (crop != null && request.glass != null &&
                    (request.blurRadiusPx <= 0 || result.fallbackBlurRadiusPx == request.blurRadiusPx)
                ) {
                    result.preparedGlass = workspace.prepare(crop, request.glass)
                }
            }
            currentCoroutineContext().ensureActive()
            prepared
        } catch (error: Throwable) {
            prepared.recycle()
            throw error
        }
    }

    private var masterSourceRect: Rect = Rect.Zero
    private var masterScaledW: Int = 0
    private var masterScaledH: Int = 0

    private var sourceRect: Rect = Rect.Zero
    private var useHardwareSnapshot = false
    private var lastCaptureTime = 0L
    private var captureRequested = false
    private var captureForced = false

    private var processingJob: Job? = null
    private var scheduledJob: Job? = null

    private val isProcessing: Boolean
        get() = processingJob?.isActive == true

    private val hasMasterCapture: Boolean
        get() = masterImage != null || masterLayer != null

    internal val shouldUseHardwareSnapshot: Boolean
        get() = useHardwareSnapshot

    internal val isHardwareAccelerationDisabled: Boolean
        get() = disableHardwareAcceleration

    /**
     * Backend for this backdrop: hardware when the platform supports it (API 33+) and hardware
     * acceleration is enabled for this backdrop, otherwise software.
     */
    internal val backend: BackdropCaptureBackend =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !disableHardwareAcceleration)
            HardwareBackdropCapture
        else
            SoftwareBackdropCapture

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
        get() = !isReplayingBackdropSource() && (activeRegionCount > 0 || captureForced)
                && processingJob?.isActive != true
                && debounced()
                && isUpdateEnabled()

    fun getResult(id: Int): CaptureResult? = regions[id]?.result?.value

    fun requestCapture() {
        requestCapture(force = false)
    }

    internal fun requestCapture(force: Boolean) {
        if (!isUpdateEnabled() || (activeRegionCount == 0 && !force)) return
        captureForced = captureForced || force
        if (captureRequested) return
        captureRequested = true
        if (isProcessing) return
        invalidateOrSchedule()
    }

    internal fun requestCaptureAfterPendingWork() {
        if (isReplayingBackdropSource()) return
        if (activeRegionCount == 0 || !isUpdateEnabled() || (debounced() && !isProcessing)) return
        requestCapture()
    }

    internal fun registerRegion(id: Int, rect: Rect, cpuBlurRadiusPx: Int, cpuGlass: CpuGlassParameters? = null) {
        val existing = regions[id]
        if (existing != null && existing.rect == rect && existing.cpuBlurRadiusPx == cpuBlurRadiusPx &&
            existing.cpuGlass == cpuGlass) return

        val isNew = existing == null
        val wasIdle = activeRegionCount == 0
        val wasActive = existing != null && intersectsSource(existing.rect)
        val isActive = intersectsSource(rect)
        val region = existing ?: Region(rect, cpuBlurRadiusPx, cpuGlass).also { regions[id] = it }
        region.rect = rect
        region.cpuBlurRadiusPx = cpuBlurRadiusPx
        region.cpuGlass = cpuGlass
        if (wasActive != isActive) activeRegionCount += if (isActive) 1 else -1

        refreshRegion(region)
        if (isNew) structureInvalidator++
        if (activeRegionCount == 0) cancelUnneededCapture()
        if (isActive && (!hasMasterCapture ||
            !hasPreparedBlur(cpuBlurRadiusPx) ||
            (wasIdle && backend.requiresContinuousCapture))
        ) {
            requestCapture()
        }
    }

    private fun refreshRegion(region: Region) {
        val oldResult = region.currentResult
        val geometry = if (hasMasterCapture && intersectsSource(region.rect)) {
            cropGeometryForRegion(region.rect, masterSourceRect, masterScaledW, masterScaledH)
        } else {
            null
        }
        val newResult = when {
            geometry == null -> null
            oldResult != null && oldResult.matches(masterImage, masterLayer, geometry) &&
                (!backend.createsFallbackBitmap || oldResult.fallbackBlurRadiusPx == region.cpuBlurRadiusPx ||
                    !hasPreparedBlur(region.cpuBlurRadiusPx)) -> oldResult
            else -> captureResultForGeometry(masterImage, masterLayer, geometry, region.cpuBlurRadiusPx, oldResult)
        }
        if (newResult !== oldResult) {
            oldResult?.recycleFallbacks(retained = newResult)
            region.currentResult = newResult
        }
    }

    internal fun updateRegionBlurRadius(id: Int, radiusPx: Int) {
        val region = regions[id] ?: return
        registerRegion(id, region.rect, radiusPx, region.cpuGlass)
    }

    internal fun updateRegionFilter(id: Int, radiusPx: Int, glass: CpuGlassParameters?) {
        val region = regions[id] ?: return
        registerRegion(id, region.rect, radiusPx, glass)
    }

    internal fun unregisterRegion(id: Int) {
        val region = regions.remove(id) ?: return
        if (intersectsSource(region.rect)) activeRegionCount--
        region.currentResult?.recycleFallbacks()
        if (activeRegionCount == 0) cancelUnneededCapture()
    }

    internal fun updateSourceRect(rect: Rect) {
        if (sourceRect == rect) return
        sourceRect = rect
        activeRegionCount = 0
        regions.values.forEach { region ->
            if (intersectsSource(region.rect)) {
                activeRegionCount++
            } else {
                region.currentResult?.recycleFallbacks()
                if (region.currentResult != null) region.currentResult = null
            }
        }
        if (activeRegionCount == 0) cancelUnneededCapture()
        requestCapture()
    }

    private fun intersectsSource(rect: Rect): Boolean = !rect.intersect(sourceRect).isEmpty

    private fun hasPreparedBlur(radius: Int): Boolean =
        !backend.createsFallbackBitmap || radius <= 0 || blurredMasters.containsKey(radius)

    private fun cancelUnneededCapture() {
        if (captureForced) return
        captureRequested = false
        scheduledJob?.cancel()
        scheduledJob = null
    }

    internal fun pauseUpdates() {
        captureForced = false
        cancelUnneededCapture()
        processingJob?.cancel()
        processingJob = null
    }

    /** Distinct nonzero blur radii currently requested by regions (legacy backend only). */
    internal fun pendingCpuBlurRadii(): Set<Int> {
        if (!backend.createsFallbackBitmap) return emptySet()
        val radii = HashSet<Int>()
        regions.values.forEach { region ->
            if (region.cpuBlurRadiusPx > 0 && intersectsSource(region.rect)) radii.add(region.cpuBlurRadiusPx)
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
        releaseAfterFrames { layers.forEach { runCatching { release(it) } } }
    }

    internal fun releaseAfterFrames(release: () -> Unit) {
        val frameContext = scope.coroutineContext[MonotonicFrameClock]
            ?.let { Dispatchers.Main.immediate + it }
            ?: AndroidUiDispatcher.Main
        val job = scope.launch(frameContext) {
            repeat(2) { withFrameNanos { } }
        }
        job.invokeOnCompletion { release() }
    }

    internal fun dispose() {
        processingJob?.cancel()
        scheduledJob?.cancel()
        if (Build.VERSION.SDK_INT >= 29) hardwarePictureReader?.retire()
        hardwarePictureReader = null
        // Drop retained arrays without mutating storage a cancelled worker may still use.
        blurWorkspace = StackBlurWorkspace()
        glassWorkspace = CpuGlassWorkspace()
        regions.values.forEach { it.currentResult?.recycleFallbacks() }
        regions.clear()
        activeRegionCount = 0
        captureRequested = false
        captureForced = false
        reusableBitmap?.safeRecycle()
        reusableBitmap = null
        masterImage?.safeRecycle()
        masterImage = null
        masterLayer = null
        blurredMasters.values.forEach { it.safeRecycle() }
        blurredMasters = emptyMap()
    }

    internal fun onPictureRecorded(picture: Picture, width: Int, height: Int, bitmapLease: BackdropBitmapLease? = null) {
        beginCapture()

        val scaledW = (width * scaleFactor).roundToInt().coerceAtLeast(1)
        val scaledH = (height * scaleFactor).roundToInt().coerceAtLeast(1)
        val currentSource = sourceRect
        val requests = snapshotSoftwareRegions()
        val session = HardwareCaptureSession(currentSource, scaledW, scaledH)
        val reference = softwareCaptureReference(session, requests)

        processingJob?.cancel()
        val pictureReader = if (Build.VERSION.SDK_INT >= 29 && !disableHardwareAcceleration && picture.requiresHardwareAcceleration()) {
            (hardwarePictureReader ?: HardwarePictureReader().also { hardwarePictureReader = it }).also { it.retain() }
        } else null
        processingJob = scope.launch(Dispatchers.Main) {
            var master: Bitmap? = null
            var capturedImage: ImageBitmap? = null
            var prepared: PreparedSoftwareCapture? = null
            var applied = false
            var unchanged = false
            try {
                // Transfer ownership only after the coroutine has started, so cancellation
                // before dispatch cannot strand a bitmap outside both the state and the job.
                master = reusableBitmap
                reusableBitmap = null
                if (master?.let { it.isRecycled || it.width != scaledW || it.height != scaledH } == true) {
                    master?.safeRecycle()
                    master = null
                }
                withContext(Dispatchers.Default) {
                    val bitmap = if (Build.VERSION.SDK_INT >= 28 && !disableHardwareAcceleration &&
                        picture.requiresHardwareAcceleration()) {
                        // The Picture already owns recorded commands/transforms, so no
                        // Compose node is touched here. GPU rendering and readback both
                        // run on the worker; GraphicsLayer.toImageBitmap() blocks Main
                        // synchronously on these Android versions despite being suspend.
                        master?.safeRecycle()
                        master = null
                        (if (Build.VERSION.SDK_INT >= 29 && pictureReader != null) {
                            pictureReader.capture(picture, scaledW, scaledH, bitmapLease)
                        } else Bitmap.createBitmap(picture, scaledW, scaledH, Bitmap.Config.ARGB_8888))
                            .also { master = it }
                    } else {
                        (master?.also { it.eraseColor(0) }
                            ?: createBitmap(scaledW, scaledH).also { master = it }).also { target ->
                            android.graphics.Canvas(target).apply {
                                scale(scaleFactor, scaleFactor)
                                drawPicture(picture)
                            }
                        }
                    }
                    val image = bitmap.asImageBitmap().also { capturedImage = it }
                    unchanged = reference?.matches(image) == true
                    if (!unchanged) prepared = prepareSoftwareCapture(image, requests, session)
                }
                reference?.close()
                if (unchanged) {
                    completeUnchangedSoftwareCapture(checkNotNull(capturedImage), reuse = true)
                    applied = true
                    return@launch
                }
                applyMasterImage(
                    masterImg = checkNotNull(capturedImage),
                    newBlurredMasters = checkNotNull(prepared).blurredMasters,
                    currentSource = currentSource,
                    scaledW = scaledW,
                    scaledH = scaledH,
                    reuseOldMaster = true,
                    prepared = prepared
                )
                applied = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                processingJob = null
                if (isHardwareBitmapSoftwareFailure(e) && !disableHardwareAcceleration) {
                    useHardwareSnapshot = true
                    lastCaptureTime = 0L
                    requestCapture()
                } else if (!isHardwareBitmapSoftwareFailure(e)) {
                    scheduleFollowUpCapture()
                }
            } finally {
                if (!applied) {
                    master?.safeRecycle()
                    prepared?.recycle()
                }
            }
        }.also { job -> job.invokeOnCompletion {
            bitmapLease?.close()
            reference?.close()
            if (Build.VERSION.SDK_INT >= 29) pictureReader?.release()
        } }
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
        if (Build.VERSION.SDK_INT >= 29) hardwarePictureReader?.retire()
        hardwarePictureReader = null
        blurWorkspace = StackBlurWorkspace()
        glassWorkspace = CpuGlassWorkspace()
        captureRequested = false
        captureForced = false
        sourceRect = Rect.Zero
        activeRegionCount = 0

        regions.values.forEach { region ->
            val result = region.currentResult
            result?.recycleFallbacks()
            if (result != null) region.currentResult = null
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
        session: HardwareCaptureSession,
        prepared: PreparedSoftwareCapture? = null
    ) {
        applyMasterImage(
            masterImg = captured,
            newBlurredMasters = newBlurredMasters,
            currentSource = session.sourceRect,
            scaledW = session.scaledW,
            scaledH = session.scaledH,
            reuseOldMaster = false,
            prepared = prepared
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
    }

    private fun applyMasterImage(
        masterImg: ImageBitmap,
        newBlurredMasters: Map<Int, ImageBitmap>,
        currentSource: Rect,
        scaledW: Int,
        scaledH: Int,
        reuseOldMaster: Boolean,
        prepared: PreparedSoftwareCapture? = null
    ) {
        val oldMaster = masterImage
        val oldBlurred = blurredMasters
        masterImage = masterImg
        masterLayer = null
        blurredMasters = newBlurredMasters
        masterSourceRect = currentSource
        masterScaledW = scaledW
        masterScaledH = scaledH

        regions.forEach { (id, region) ->
            val candidate = prepared?.regions?.remove(id)
            val geometry = if (intersectsSource(region.rect)) {
                cropGeometryForRegion(region.rect, currentSource, scaledW, scaledH)
            } else null
            val newResult = when {
                geometry == null -> null
                candidate != null && candidate.matches(masterImg, null, geometry) &&
                    candidate.fallbackBlurRadiusPx == (region.cpuBlurRadiusPx.takeIf { newBlurredMasters.containsKey(it) } ?: 0)
                    -> candidate
                else -> captureResultForGeometry(masterImg, null, geometry, region.cpuBlurRadiusPx, candidate)
            }
            candidate?.recycleFallbacks(retained = newResult)
            region.currentResult?.recycleFallbacks()
            region.currentResult = newResult
        }
        // Consumers can disappear while pixels are being prepared on the worker.
        prepared?.recycleRegions()

        recycleOrReuseOldMaster(oldMaster, reuseOldMaster)
        oldBlurred.values.forEach { it.safeRecycle() }
        processingJob = null

        scheduleFollowUpCapture()
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

        // A stable source layer already updates the retained GPU tree. Only changed
        // crop geometry needs a new result and a consumer display-list recording.
        regions.values.forEach(::refreshRegion)

        recycleOrReuseOldMaster(oldMaster, reuseOldMaster = false)
        oldBlurred.values.forEach { it.safeRecycle() }
        processingJob = null

        scheduleFollowUpCapture()
    }

    private fun beginCapture() {
        captureRequested = false
        captureForced = false
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
        if (!bitmap.isRecycled && bitmap.isMutable && !BackdropBitmapReferences.isRetained(bitmap)) {
            reusableBitmap?.takeIf { it !== bitmap }?.safeRecycle()
            reusableBitmap = bitmap
        } else {
            oldMaster.safeRecycle()
        }
    }

    // A live display list is cheap to publish and must advance with its source draw.
    // Throttling it to the snapshot cadence drops animation frames through layer chains.
    private fun debounced(): Boolean =
        !backend.requiresContinuousCapture || (SystemClock.uptimeMillis() - lastCaptureTime) >= debounceMs

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

    /**
     * Continues capturing after a capture completes. Explicit requests always win; on
     * snapshot backends ([BackdropCaptureBackend.requiresContinuousCapture]) or
     * software-only capture, the next capture is additionally scheduled at debounce
     * cadence while regions intersect the source, so a moving background keeps refreshing a static
     * glass component. The loop is
     * self-limiting: it only reschedules from capture completion, and captures only
     * happen during draws, so it idles when no frames are produced and stops while
     * updates are frozen or no regions remain.
     */
    private fun scheduleFollowUpCapture() {
        if (captureRequested) {
            invalidateOrSchedule()
            return
        }
        if (!backend.requiresContinuousCapture) return
        if (activeRegionCount == 0 || !isUpdateEnabled()) return
        if (scheduledJob?.isActive == true) return
        scheduledJob = scope.launch {
            val remaining = (debounceMs - (SystemClock.uptimeMillis() - lastCaptureTime)).coerceAtLeast(1L)
            delay(remaining.milliseconds)
            if (activeRegionCount > 0 && isUpdateEnabled()) sourceInvalidator++
        }
    }

    private fun cropGeometryForRegion(
        regionRect: Rect,
        source: Rect,
        scaledW: Int,
        scaledH: Int
    ): CropGeometry? {
        val intersection = regionRect.intersect(source)
        if (intersection.isEmpty) return null

        val rawCrop = intersection
            .translate(-source.left, -source.top)
            .scale(scaleFactor)

        val l = rawCrop.left.roundToInt().coerceIn(0, scaledW)
        val t = rawCrop.top.roundToInt().coerceIn(0, scaledH)
        val r = rawCrop.right.roundToInt().coerceIn(0, scaledW)
        val b = rawCrop.bottom.roundToInt().coerceIn(0, scaledH)

        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null

        val visibleOffset = intersection.topLeft - regionRect.topLeft
        val sampleOffset = Offset(
            x = (l - rawCrop.left) / scaleFactor,
            y = (t - rawCrop.top) / scaleFactor
        )

        val visibleSize = IntSize(
            width = intersection.width.roundToInt().coerceAtLeast(1),
            height = intersection.height.roundToInt().coerceAtLeast(1)
        )

        return CropGeometry(
            srcOffset = IntOffset(l, t),
            srcSize = IntSize(w, h),
            drawOffset = visibleOffset,
            sampleOffset = sampleOffset,
            drawSize = visibleSize
        )
    }

    private fun cropForRegion(
        region: Region,
        source: Rect,
        masterImage: ImageBitmap?,
        masterLayer: GraphicsLayer?,
        scaledW: Int,
        scaledH: Int
    ): CaptureResult? {
        if (!intersectsSource(region.rect)) return null
        val geometry = cropGeometryForRegion(region.rect, source, scaledW, scaledH) ?: return null
        return captureResultForGeometry(masterImage, masterLayer, geometry, region.cpuBlurRadiusPx)
    }

    private fun captureResultForGeometry(
        masterImage: ImageBitmap?,
        masterLayer: GraphicsLayer?,
        geometry: CropGeometry,
        regionBlurRadiusPx: Int,
        previous: CaptureResult? = null,
        availableBlurredMasters: Map<Int, ImageBitmap> = blurredMasters
    ): CaptureResult {
        var fallbackBlurRadiusPx = 0
        val fallback = if (backend.createsFallbackBitmap && masterImage != null) {
            val cropSource = availableBlurredMasters[regionBlurRadiusPx]
                ?.also { fallbackBlurRadiusPx = regionBlurRadiusPx }
                ?: masterImage
            if (previous != null && previous.masterImage === masterImage &&
                previous.srcOffset == geometry.srcOffset && previous.srcSize == geometry.srcSize &&
                previous.fallbackBlurRadiusPx == fallbackBlurRadiusPx && previous.fallbackBitmap != null
            ) {
                previous.fallbackBitmap
            } else cropBitmap(
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
            sampleOffset = geometry.sampleOffset,
            drawSize = geometry.drawSize,
            fallbackBitmap = fallback,
            fallbackBlurRadiusPx = fallbackBlurRadiusPx,
            captureScaleFactor = scaleFactor
        ).also { result ->
            if (fallback != null && fallback === previous?.fallbackBitmap) {
                result.preparedGlass = previous.preparedGlass
            }
        }
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
                sampleOffset == geometry.sampleOffset &&
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
    retainBackdropBitmap(bitmap)
    if (result == null) {
        drawImage(
            image = bitmap,
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )
        return
    }

    val scale = result.captureScaleFactor.coerceAtLeast(0.001f)
    val sampledDrawSize = IntSize(
        width = (result.srcSize.width / scale).roundToInt().coerceAtLeast(1),
        height = (result.srcSize.height / scale).roundToInt().coerceAtLeast(1)
    )

    translate(result.drawOffset.x, result.drawOffset.y) {
        clipRect(
            left = 0f,
            top = 0f,
            right = result.drawSize.width.toFloat(),
            bottom = result.drawSize.height.toFloat()
        ) {
            translate(result.sampleOffset.x, result.sampleOffset.y) {
                drawImage(bitmap, dstSize = sampledDrawSize)
            }
        }
    }
}

// =============================================================================
// CPU BLUR FALLBACK (< API 33)
// =============================================================================

/**
 * Per-node cache for transitional draw-time CPU effects on the legacy path. Steady-state
 * crops and glass refraction are prepared on the capture worker. These caches cover
 * movement or filter changes that cannot yet use a worker-prepared result.
 */
internal class CpuBlurCache {
    private val blurWorkspace = StackBlurWorkspace()
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
    private val glassMaps = CpuRefractionMapCache(maxPixels = 256 * 1024, maxEntries = 1)

    fun blur(sourceBitmap: Bitmap, radiusPx: Int): Bitmap {
        if (radiusPx < 1) {
            clear()
            return sourceBitmap
        }

        if (sourceBitmap === source && radiusPx == radius) {
            result?.let { if (!it.isRecycled) return it }
        }

        val blurred = applyStackBlur(sourceBitmap.copy(Bitmap.Config.ARGB_8888, true), radiusPx, blurWorkspace)
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
        val blurred = if (radius > 0) applyStackBlur(base, radius, blurWorkspace) else base
        val pixelCount = blurred.width * blurred.height
        if (glassPixels.size < pixelCount) glassPixels = IntArray(pixelCount)
        if (glassOutput.size < pixelCount) glassOutput = IntArray(pixelCount)

        val refracted = applyCpuGlassRefraction(
            bitmap = blurred,
            refraction = glass.refraction,
            edge = glass.edge,
            pixels = glassPixels,
            output = glassOutput,
            maps = glassMaps
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

    fun clearResults() {
        if (source == null && glassSource == null) return
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
    }

    fun clear() {
        clearResults()
        glassMaps.clear()
        glassPixels = IntArray(0)
        glassOutput = IntArray(0)
        blurWorkspace.clear()
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

    var pending: Bitmap? = null
    return try {
        val out = createBitmap(w, h)
        pending = out
        android.graphics.Canvas(out).drawBitmap(
            source.asAndroidBitmap(),
            android.graphics.Rect(l, t, r, b),
            android.graphics.Rect(0, 0, w, h),
            bitmapFilterPaint()
        )
        out.asImageBitmap().also { pending = null }
    } catch (e: Exception) {
        null
    } finally {
        pending?.safeRecycle()
    }
}
