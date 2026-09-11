package com.builditcode.glass

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Only the parameters that affect software refraction pixels. Tint and clipping stay in draw. */
internal data class CpuGlassParameters(val refraction: Float, val edge: Float)

internal fun BackdropFilter.cpuGlassParameters(): CpuGlassParameters? =
    (this as? BackdropFilter.Glass)?.let { CpuGlassParameters(it.refraction, it.edge) }

/** Immutable main-thread snapshot: background preparation never reads the live region map. */
internal data class SoftwareRegionRequest(
    val id: Int,
    val rect: Rect,
    val blurRadiusPx: Int,
    val glass: CpuGlassParameters?
)

internal data class PreparedGlass(val bitmap: ImageBitmap, val parameters: CpuGlassParameters)

/** Retains the published pixels while a worker checks a newly captured frame. */
internal class SoftwareCaptureReference(private val image: ImageBitmap) : AutoCloseable {
    private val lease = BackdropBitmapLease().apply { retain(image) }

    fun matches(captured: ImageBitmap): Boolean = image.asAndroidBitmap().sameAs(captured.asAndroidBitmap())

    override fun close() = lease.close()
}

internal fun BackdropState.CaptureResult.preparedGlassFor(
    glass: BackdropFilter.Glass,
    residualRadiusPx: Int
): ImageBitmap? = preparedGlass?.takeIf {
    residualRadiusPx <= 0 && it.parameters.refraction == glass.refraction && it.parameters.edge == glass.edge
}?.bitmap

/** Owns all unpublished bitmaps, including when cancellation discards a worker's result. */
internal class PreparedSoftwareCapture(
    val blurredMasters: Map<Int, ImageBitmap>,
    val regions: MutableMap<Int, BackdropState.CaptureResult> = HashMap()
) {
    fun recycleRegions() {
        regions.values.forEach { it.recycleFallbacks() }
        regions.clear()
    }

    fun recycle() {
        recycleRegions()
        blurredMasters.values.forEach { it.safeRecycle() }
    }
}

internal fun BackdropState.CaptureResult.recycleFallbacks(retained: BackdropState.CaptureResult? = null) {
    if (fallbackBitmap !== retained?.fallbackBitmap) fallbackBitmap?.safeRecycle()
    if (preparedGlass?.bitmap !== retained?.preparedGlass?.bitmap) preparedGlass?.bitmap?.safeRecycle()
}

/** Shared serially by a source's worker, instead of retaining two pixel arrays per glass node. */
internal class CpuGlassWorkspace {
    private var pixels = IntArray(0)
    private var output = IntArray(0)
    private val maps = CpuRefractionMapCache()

    fun prepare(source: ImageBitmap, parameters: CpuGlassParameters): PreparedGlass? {
        val bitmap = source.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true) ?: return null
        try {
            val count = bitmap.width * bitmap.height
            if (pixels.size < count) pixels = IntArray(count)
            if (output.size < count) output = IntArray(count)
            applyCpuGlassRefraction(bitmap, parameters.refraction, parameters.edge, pixels, output, maps)
            return PreparedGlass(bitmap.asImageBitmap(), parameters)
        } catch (error: Throwable) {
            bitmap.safeRecycle()
            throw error
        }
    }
}
