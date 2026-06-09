package com.builditcode.glass

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Provides the [BackdropLayerManager] shared by [layeredBackdropSource] and [layeredBackdropCapture]
 * within a subtree. Null when no manager has been provided, in which case the glass modifiers are
 * no-ops.
 */
val LocalBackdropLayerManager = staticCompositionLocalOf<BackdropLayerManager?> { null }

/**
 * Creates and remembers a [BackdropLayerManager], disposing it when it leaves the composition.
 *
 * Provide the result through [LocalBackdropLayerManager] (as [TriLevelLayout]/[QuadLevelLayout] do)
 * so every [layeredBackdropSource] and [layeredBackdropCapture] in the subtree shares capture state.
 *
 * @param defaultScaleFactor Resolution scale (0..1) every layer captures at; lower is cheaper but
 * blurrier.
 */
@Composable
fun rememberBackdropManager(
    defaultScaleFactor: Float = 0.5f,
): BackdropLayerManager {
    val manager = remember(defaultScaleFactor) {
        BackdropLayerManager(defaultScaleFactor)
    }
    DisposableEffect(manager) { onDispose { manager.disposeAll() } }
    return manager
}

/**
 * Holds the most recent backdrop capture for each named layer and lets glass components sample it.
 *
 * A single manager is shared (via [LocalBackdropLayerManager]) by a set of cooperating
 * [layeredBackdropSource] producers and [layeredBackdropCapture] consumers — usually created for you
 * by [TriLevelLayout]/[QuadLevelLayout] through [rememberBackdropManager].
 *
 * @property defaultScaleFactor Resolution scale (0..1) sources capture at; applied to every layer.
 */
@Stable
class BackdropLayerManager(
    val defaultScaleFactor: Float = 0.5f
) {
    // Each layer gets its own MutableState so a consumer sampling layer A only recomposes when layer A
    // republishes — not when some other layer updates. A shared SnapshotStateMap tracks reads coarsely
    // and would notify every consumer on any key change. The outer map is only touched on the main
    // thread (composition + draw recording), so a plain map is safe.
    private val captures = mutableMapOf<String, MutableState<BackdropLayerCapture?>>()

    private fun stateFor(layerName: String): MutableState<BackdropLayerCapture?> =
        captures.getOrPut(layerName) { mutableStateOf(null) }

    /**
     * When false, [setCapture] is ignored so the last captured image stays frozen — e.g. while an
     * overlay opens, to avoid capturing the overlay into its own blur. Toggle via [stopUpdates] /
     * [startUpdates].
     */
    var shouldUpdate: Boolean by mutableStateOf(true)
        private set

    /** Freezes captures; the last published image for every layer keeps being sampled. */
    fun stopUpdates() {
        shouldUpdate = false
    }

    /** Resumes captures after [stopUpdates]. */
    fun startUpdates() {
        shouldUpdate = true
    }

    /**
     * Forces consumers of [layerName] to re-read the current capture by bumping its version, without
     * recapturing. Used when a consumer moves and must re-sample even though the source is unchanged.
     */
    fun invalidate(layerName: String) {
        val state = captures[layerName] ?: return
        val current = state.value ?: return
        state.value = current.copy(version = current.version + 1)
    }

    /**
     * Publishes a freshly captured [image] for [layerName]. Called by [layeredBackdropSource]; most
     * code never invokes this directly. No-op while [shouldUpdate] is false.
     *
     * The capture's version increments on each publish so consumers observing it are reliably
     * notified — important because the source reuses a small set of bitmap buffers.
     *
     * @param positionInRoot Root-space top-left of the captured source, used to align consumer crops.
     * @param scaleFactor Scale the [image] was captured at.
     */
    fun setCapture(
        layerName: String,
        image: ImageBitmap,
        positionInRoot: Offset,
        scaleFactor: Float
    ) {
        if (!shouldUpdate) return
        val state = stateFor(layerName)
        val nextVersion = (state.value?.version ?: 0) + 1
        state.value = BackdropLayerCapture(image, positionInRoot, scaleFactor, nextVersion)
    }

    /** Returns the latest capture published for [layerName], or null if none exists yet. */
    fun getLayerCapture(layerName: String): BackdropLayerCapture? = stateFor(layerName).value

    /** Drops all captures. Call when the manager leaves the composition. */
    fun disposeAll() {
        captures.values.forEach { it.value = null }
        captures.clear()
    }
}

/**
 * Marks this composable as the backdrop source named [layerName].
 *
 * The subtree's rendered pixels are captured (at the manager's scale) and made available to
 * [layeredBackdropCapture] consumers sampling the same [layerName]. Requires a
 * [LocalBackdropLayerManager]; without one the modifier is a no-op.
 *
 * A consumer must sample a source *behind* it, never the layer it lives in — same-layer sampling is
 * a feedback loop and is not supported.
 */
fun Modifier.layeredBackdropSource(layerName: String): Modifier = composed {
    val manager = LocalBackdropLayerManager.current ?: return@composed this
    var sourcePositionInRoot by remember { mutableStateOf(Offset.Zero) }

    this
        .onGloballyPositioned { coordinates ->
            sourcePositionInRoot = coordinates.positionInRoot()
        }
        .captureToBitmapCached(
            captureEnabled = manager.shouldUpdate,
            scaleFactor = manager.defaultScaleFactor,
            onBitmapCaptured = { bitmap ->
                manager.setCapture(
                    layerName = layerName,
                    image = bitmap,
                    positionInRoot = sourcePositionInRoot,
                    scaleFactor = manager.defaultScaleFactor
                )
            }
        )
}

/**
 * Samples the [layerName] backdrop behind this component and draws it blurred/refracted, clipped to
 * [shape] (falling back to [BackdropFilter.shape]).
 *
 * On API 33+ the source is sampled live and the effect runs on the GPU as a `RenderEffect`; below 33
 * it falls back to a CPU stack blur (plus an approximate CPU refraction for [BackdropFilter.Glass]).
 * Requires a [LocalBackdropLayerManager]; without one the modifier is a no-op.
 *
 * [layerName] must refer to a layer *behind* this component, never the one it lives in.
 *
 * @param layerName Source layer to sample.
 * @param shape Clip (and, for glass, refraction) shape. Defaults to the [filter]'s shape.
 * @param padding Inset applied before sampling and clipping.
 * @param filter Blur or glass effect to apply to the sampled backdrop.
 * @param autoInvalidateOnMove When true, this component re-samples the layer as it moves so the crop
 * tracks its new position.
 */
fun Modifier.layeredBackdropCapture(
    layerName: String,
    shape: Shape? = null,
    padding: PaddingValues = PaddingValues(0.dp),
    filter: BackdropFilter = BackdropFilter.Blur(),
    autoInvalidateOnMove: Boolean = true
): Modifier = composed {
    val manager = LocalBackdropLayerManager.current ?: return@composed this
    val captureShape = shape ?: filter.shape
    val useGpu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var targetRectInRoot by remember { mutableStateOf(Rect.Zero) }
    // Read the capture in composition: a publish bumps the per-layer state's version, recomposing and
    // redrawing this consumer. A Modifier.Node that observed/invalidated in draw was tried but a
    // static consumer over an animating source froze — the source writes the capture during its own
    // draw phase, which a draw-time consumer can't react to until some other invalidation (a scroll)
    // kickstarts it. Composition observation is the reliable path. Position is read in draw below.
    val sourceCapture = manager.getLayerCapture(layerName)

    val glassShader = remember(useGpu) {
        if (useGpu) RuntimeShader(GLASS_SHADER) else null
    }
    val gpuLayer = rememberGraphicsLayer()
    val effectCache = remember { GlassEffectCache() }
    val cpuCache = remember { CpuBackdropCache() }

    val padded = this.padding(padding)
    val clipped = if (captureShape != null) padded.clip(captureShape) else padded
    clipped
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            val size = coordinates.size.toSize()
            val nextRect = Rect(
                left = position.x,
                top = position.y,
                right = position.x + size.width,
                bottom = position.y + size.height
            )
            if (autoInvalidateOnMove && nextRect != targetRectInRoot) {
                manager.invalidate(layerName)
            }
            targetRectInRoot = nextRect
        }
        .drawWithContent {
            val capture = sourceCapture
            if (capture != null) {
                if (useGpu && glassShader != null) {
                    drawGpuBackdrop(
                        capture = capture,
                        targetRectInRoot = targetRectInRoot,
                        filter = filter,
                        shape = captureShape,
                        shader = glassShader,
                        layer = gpuLayer,
                        effectCache = effectCache
                    )
                } else {
                    cpuCache.get(capture, targetRectInRoot, filter, this)?.let { cropped ->
                        translate(cropped.drawOffset.x, cropped.drawOffset.y) {
                            drawImage(
                                image = cropped.image,
                                dstSize = cropped.drawSize,
                                filterQuality = FilterQuality.Medium
                            )
                        }
                        if (filter.tint != Color.Transparent) drawRect(filter.tint)
                    }
                }
            }
            drawContent()
        }
}

/**
 * Renders this content into an offscreen [ImageBitmap] (downscaled by [scaleFactor]) after drawing it
 * normally, then hands the bitmap to [onBitmapCaptured]. Backs [layeredBackdropSource]; double-buffered
 * so a consumer never samples a buffer that is mid-overwrite.
 *
 * @param onBitmapCaptured Receives the freshly captured bitmap on each draw while [captureEnabled].
 * @param captureEnabled When false the content draws normally but nothing is captured.
 * @param scaleFactor Capture resolution scale, coerced to 0.05..1.
 */
fun Modifier.captureToBitmapCached(
    onBitmapCaptured: (ImageBitmap) -> Unit,
    captureEnabled: Boolean = false,
    scaleFactor: Float = 1f
): Modifier = drawWithCache {
    val captureScale = scaleFactor.coerceIn(0.05f, 1f)
    val bitmapWidth = (size.width * captureScale).roundToInt().coerceAtLeast(1)
    val bitmapHeight = (size.height * captureScale).roundToInt().coerceAtLeast(1)
    // Double-buffered: render into one bitmap while the previously published one is still being
    // sampled by consumers, so a consumer never reads a buffer that is mid-overwrite.
    val buffers = arrayOf(
        ImageBitmap(width = bitmapWidth, height = bitmapHeight),
        ImageBitmap(width = bitmapWidth, height = bitmapHeight)
    )
    // Canvas objects are bound to their bitmap once and reused, so the per-frame capture allocates
    // nothing here.
    val canvases = Array(buffers.size) { Canvas(buffers[it]) }
    var next = 0

    onDrawWithContent {
        drawContent()

        if (captureEnabled) {
            val index = next
            next = (next + 1) % buffers.size
            val bitmap = buffers[index]
            bitmap.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
            draw(this, layoutDirection, canvases[index], size) {
                scale(captureScale, captureScale, pivot = Offset.Zero) {
                    this@onDrawWithContent.drawContent()
                }
            }
            onBitmapCaptured(bitmap)
        }
    }
}

/**
 * GPU backdrop draw (API 33+): draws the whole downscaled source into [layer], translated so the
 * source pixel under this region's top-left lands at the layer origin (float translate + 1/scale dst
 * size keeps it pinned at any sub-pixel scroll offset), then applies the cached glass/blur effect.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun ContentDrawScope.drawGpuBackdrop(
    capture: BackdropLayerCapture,
    targetRectInRoot: Rect,
    filter: BackdropFilter,
    shape: Shape?,
    shader: RuntimeShader,
    layer: GraphicsLayer,
    effectCache: GlassEffectCache
) {
    val s = capture.scaleFactor.coerceIn(0.05f, 1f)
    val layerSize = IntSize(
        size.width.roundToInt().coerceAtLeast(1),
        size.height.roundToInt().coerceAtLeast(1)
    )
    val sourceImage = capture.image
    val sourceDstSize = IntSize(
        (sourceImage.width / s).roundToInt().coerceAtLeast(1),
        (sourceImage.height / s).roundToInt().coerceAtLeast(1)
    )
    val offsetX = capture.positionInRoot.x - targetRectInRoot.left
    val offsetY = capture.positionInRoot.y - targetRectInRoot.top
    layer.record(this, layoutDirection, layerSize) {
        translate(offsetX, offsetY) {
            drawImage(sourceImage, dstSize = sourceDstSize, filterQuality = FilterQuality.High)
        }
    }
    layer.renderEffect = when (val f = filter) {
        is BackdropFilter.Blur -> effectCache.blur(f.radius.toPx())
        is BackdropFilter.Glass -> effectCache.glass(
            width = size.width,
            height = size.height,
            density = this,
            glass = f,
            shape = shape,
            layoutDirection = layoutDirection,
            shader = shader
        )
    }
    drawLayer(layer)
    (filter as? BackdropFilter.Blur)?.tint?.let { drawRect(it) }
}

/** Caches the GPU [RenderEffect] so it is only rebuilt when the size or filter actually changes. */
private class GlassEffectCache {
    private var blurKey = Float.NaN
    private var blurEffect: androidx.compose.ui.graphics.RenderEffect? = null

    /** Returns the cached blur effect for [blurPx], rebuilding only when it changes (null when <= 0). */
    @RequiresApi(Build.VERSION_CODES.S)
    fun blur(blurPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (blurKey != blurPx) {
            blurEffect = if (blurPx > 0f) {
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP).asComposeRenderEffect()
            } else {
                null
            }
            blurKey = blurPx
        }
        return blurEffect
    }

    private var width = -1f
    private var height = -1f
    private var densityValue = -1f
    private var glass: BackdropFilter.Glass? = null
    private var shape: Shape? = null
    private var layoutDirection: LayoutDirection? = null
    private var glassEffect: androidx.compose.ui.graphics.RenderEffect? = null

    /**
     * Returns the cached glass effect, rebuilding only when the size, density, filter, [shape] or
     * layout direction change. Corner radii are resolved here (not every draw), so the steady-state
     * call is allocation-free — it neither builds a `RenderEffect` nor an `Outline`.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun glass(
        width: Float,
        height: Float,
        density: Density,
        glass: BackdropFilter.Glass,
        shape: Shape?,
        layoutDirection: LayoutDirection,
        shader: RuntimeShader
    ): androidx.compose.ui.graphics.RenderEffect {
        val cached = glassEffect
        if (cached != null && this.width == width && this.height == height &&
            this.densityValue == density.density && this.glass == glass &&
            this.shape == shape && this.layoutDirection == layoutDirection
        ) {
            return cached
        }
        val radii = shape.resolveCornerRadiiPx(Size(width, height), layoutDirection, density)
        val effect = glassRenderEffect(glass, shader, width, height, density.density, radii)
        this.width = width
        this.height = height
        this.densityValue = density.density
        this.glass = glass
        this.shape = shape
        this.layoutDirection = layoutDirection
        glassEffect = effect
        return effect
    }
}

/** Caches the cropped + CPU-blurred backdrop (pre-33) so it is only recomputed when inputs change. */
private class CpuBackdropCache {
    private var version = Int.MIN_VALUE
    private var rect = Rect.Zero
    private var filter: BackdropFilter? = null
    private var result: CroppedBackdrop? = null

    /** Recomputes only when the capture version, [targetRectInRoot] or [filter] change; cached otherwise. */
    fun get(
        capture: BackdropLayerCapture,
        targetRectInRoot: Rect,
        filter: BackdropFilter,
        density: Density
    ): CroppedBackdrop? {
        if (result == null || version != capture.version || rect != targetRectInRoot || this.filter != filter) {
            result = capture.renderFor(targetRectInRoot, filter, density)
            version = capture.version
            rect = targetRectInRoot
            this.filter = filter
        }
        return result
    }
}

/**
 * Crops [targetRectInRoot] out of the (scaled) source bitmap. The crop is expanded to whole source
 * pixels (floor/ceil) and the fractional remainder is returned as [CroppedRegion.drawOffset] so the
 * caller can re-align it precisely — this is what removes the scroll jitter.
 */
private fun BackdropLayerCapture.cropRegion(targetRectInRoot: Rect): CroppedRegion? {
    if (targetRectInRoot == Rect.Zero) return null
    val s = scaleFactor.coerceIn(0.05f, 1f)
    val rawLeft = (targetRectInRoot.left - positionInRoot.x) * s
    val rawTop = (targetRectInRoot.top - positionInRoot.y) * s
    val rawRight = (targetRectInRoot.right - positionInRoot.x) * s
    val rawBottom = (targetRectInRoot.bottom - positionInRoot.y) * s

    val left = floor(rawLeft).toInt().coerceIn(0, image.width)
    val top = floor(rawTop).toInt().coerceIn(0, image.height)
    val right = ceil(rawRight).toInt().coerceIn(0, image.width)
    val bottom = ceil(rawBottom).toInt().coerceIn(0, image.height)
    val cropWidth = right - left
    val cropHeight = bottom - top
    if (cropWidth <= 0 || cropHeight <= 0) return null

    val bitmap = image.cropPixels(left, top, cropWidth, cropHeight) ?: return null
    val drawOffset = Offset((left - rawLeft) / s, (top - rawTop) / s)
    val drawSize = IntSize(
        (cropWidth / s).roundToInt().coerceAtLeast(1),
        (cropHeight / s).roundToInt().coerceAtLeast(1)
    )
    return CroppedRegion(bitmap, drawOffset, drawSize)
}

/**
 * Builds the AGSL glass [android.graphics.RenderEffect]: feeds the [GLASS_SHADER] uniforms for the
 * given [width]/[height]/[radii]/[glass] params, then chains a blur before it when the blur radius is
 * positive. The shader samples its `content` input (the recorded backdrop layer).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun glassRenderEffect(
    glass: BackdropFilter.Glass,
    shader: RuntimeShader,
    width: Float,
    height: Float,
    density: Float,
    radii: FloatArray
): androidx.compose.ui.graphics.RenderEffect {
    shader.setFloatUniform("resolution", width, height)
    shader.setFloatUniform("lensCenter", width / 2f, height / 2f)
    shader.setFloatUniform("lensSize", width, height)
    shader.setFloatUniform("cornerRadii", radii[0], radii[1], radii[2], radii[3])
    shader.setFloatUniform("refraction", glass.refraction)
    shader.setFloatUniform("dispersion", glass.dispersion)
    shader.setFloatUniform("edge", glass.edge)
    shader.setFloatUniform(
        "tint",
        glass.tint.red, glass.tint.green, glass.tint.blue, glass.tint.alpha
    )

    val glassEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
    val blurPx = glass.blurRadiusIntensity * 2f * density
    val combined = if (blurPx > 0f) {
        RenderEffect.createChainEffect(
            glassEffect,
            RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        )
    } else {
        glassEffect
    }
    return combined.asComposeRenderEffect()
}

/**
 * Resolves this shape's four corner radii in pixels as `[topLeft, topRight, bottomRight, bottomLeft]`,
 * or all zeros when the shape is null or not rounded.
 */
private fun Shape?.resolveCornerRadiiPx(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density
): FloatArray {
    if (this == null) return FloatArray(4)
    return when (val outline = createOutline(size, layoutDirection, density)) {
        is Outline.Rounded -> {
            val rr = outline.roundRect
            floatArrayOf(
                min(rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y),
                min(rr.topRightCornerRadius.x, rr.topRightCornerRadius.y),
                min(rr.bottomRightCornerRadius.x, rr.bottomRightCornerRadius.y),
                min(rr.bottomLeftCornerRadius.x, rr.bottomLeftCornerRadius.y)
            )
        }

        else -> FloatArray(4)
    }
}

/** Crops the captured source and applies the CPU stack-blur / refraction (pre-API 33 path). */
private fun BackdropLayerCapture.renderFor(
    targetRectInRoot: Rect,
    filter: BackdropFilter,
    density: Density
): CroppedBackdrop? {
    val region = cropRegion(targetRectInRoot) ?: return null
    return CroppedBackdrop(
        region.bitmap.applyBackdropFilter(filter, density).asImageBitmap(),
        region.drawOffset,
        region.drawSize
    )
}

/** Returns a new bitmap holding the [left]/[top]/[cropWidth]/[cropHeight] sub-rect of this image. */
private fun ImageBitmap.cropPixels(left: Int, top: Int, cropWidth: Int, cropHeight: Int): Bitmap? {
    if (cropWidth <= 0 || cropHeight <= 0) return null

    val result = createBitmap(cropWidth, cropHeight)
    val canvas = android.graphics.Canvas(result)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(
        asAndroidBitmap(),
        AndroidRect(left, top, left + cropWidth, top + cropHeight),
        AndroidRect(0, 0, cropWidth, cropHeight),
        paint
    )
    return result
}

/**
 * Applies the CPU equivalent of [filter] — stack blur, plus refraction for glass — in place on the
 * receiver. The tint is not baked in here; both the CPU and GPU-blur paths draw it as a rect over the
 * result (and the GPU glass path mixes it in the shader), so there is a single tint path per backend.
 */
private fun Bitmap.applyBackdropFilter(filter: BackdropFilter, density: Density): Bitmap {
    // The receiver is the freshly cropped, exclusively-owned mutable bitmap from cropPixels, so we
    // blur/refract it in place — no defensive copy. The crop becomes the result.
    when (filter) {
        is BackdropFilter.Blur -> {
            val radiusPx = with(density) { filter.radius.toPx() }.roundToInt()
            if (radiusPx > 0) applyStackBlur(this, radiusPx)
        }

        is BackdropFilter.Glass -> {
            val radiusPx = (filter.blurRadiusIntensity * density.density).roundToInt()
            if (radiusPx > 0) applyStackBlur(this, radiusPx)
            val pixelCount = width * height
            applyCpuGlassRefraction(
                bitmap = this,
                refraction = filter.refraction,
                edge = filter.edge,
                pixels = IntArray(pixelCount),
                output = IntArray(pixelCount)
            )
        }
    }
    return this
}



/**
 * One layer's most recent capture.
 *
 * @property image The captured pixels, downscaled by [scaleFactor].
 * @property positionInRoot Root-space top-left of the captured source, used to align consumer crops.
 * @property scaleFactor Scale the [image] was captured at.
 * @property version Increments on every publish/[BackdropLayerManager.invalidate] so consumers
 * observing the capture are reliably notified even when the backing bitmap buffer is reused.
 */
@Stable
data class BackdropLayerCapture(
    val image: ImageBitmap,
    val positionInRoot: Offset,
    val scaleFactor: Float,
    val version: Int = 0
)

/** A backdrop effect applied by [layeredBackdropCapture]. */
@Stable
sealed interface BackdropFilter {
    /** Clip (and, for glass, refraction) shape, or null to use the modifier's own shape. */
    val shape: Shape?

    /** Color overlaid on the sampled backdrop — drawn over it (Blur) or mixed in the shader (Glass). */
    val tint: Color

    /**
     * A plain Gaussian blur of the sampled backdrop.
     *
     * @param radius Blur radius.
     * @param tint Color drawn over the blurred backdrop.
     * @param shape Optional clip shape override.
     */
    @Stable
    data class Blur(
        val radius: Dp = 20.dp,
        override val tint: Color = Color.Transparent,
        override val shape: Shape? = null
    ) : BackdropFilter

    /**
     * A liquid-glass effect: blur plus edge refraction, chromatic dispersion and rim lighting.
     * Refraction/dispersion/rim are GPU-only (API 33+); below 33 only blur and an approximate CPU
     * refraction are applied.
     *
     * @param blurRadiusIntensity Blur strength (scaled by display density).
     * @param refraction Edge-bending amount.
     * @param dispersion Chromatic separation amount (GPU only).
     * @param edge Rim-light intensity at the shape edge.
     * @param tint Color mixed over the result.
     * @param shape Optional clip/refraction shape override.
     */
    @Stable
    data class Glass(
        val blurRadiusIntensity: Float = 4f,
        val refraction: Float = 0.2f,
        val dispersion: Float = 0.2f,
        val edge: Float = 0.25f,
        override val tint: Color = Color.Transparent,
        override val shape: Shape? = null
    ) : BackdropFilter
}


/**
 * A cropped backdrop region ready to draw. [drawOffset]/[drawSize] place the crop at exactly the
 * right sub-pixel position so the backdrop stays pinned to the source while the region scrolls,
 * instead of snapping by whole source pixels each frame.
 */
@Stable
private class CroppedBackdrop(
    val image: ImageBitmap,
    val drawOffset: Offset,
    val drawSize: IntSize
)

/** Intermediate crop result holding a raw [Bitmap] before it is filtered into a [CroppedBackdrop]. */
@Stable
private class CroppedRegion(
    val bitmap: Bitmap,
    val drawOffset: Offset,
    val drawSize: IntSize
)
