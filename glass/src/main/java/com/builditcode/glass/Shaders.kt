package com.builditcode.glass

import android.graphics.Bitmap
import org.intellij.lang.annotations.Language
import kotlin.math.abs
import kotlin.math.min


// =============================================================================
// AGSL GLASS SHADER
// =============================================================================

@Language("AGSL")
internal val GLASS_SHADER = """
    // Half extents and their reciprocals; optical constants are prepared per node.
    uniform float4 lens;
    uniform float4 cornerRadii;
    // Bevel width, reciprocal width, rim displacement, interior displacement.
    uniform float4 optics;
    uniform float dispersionPx;
    uniform float edge;
    uniform float edgeWidth;
    layout(color) uniform half4 tint;
    uniform shader content;

    const float AA = 1.5;
    const float2 LIGHT_DIRECTION = float2(-0.70710678, -0.70710678);

    float cornerRadiusFor(float2 p) {
        if (p.y < 0.0) return p.x < 0.0 ? cornerRadii.x : cornerRadii.y;
        return p.x >= 0.0 ? cornerRadii.z : cornerRadii.w;
    }

    half4 main(float2 fc) {
        float2 halfExt = lens.xy;
        float2 lp = fc - halfExt;
        float cr = min(cornerRadiusFor(lp), min(halfExt.x, halfExt.y));
        float2 q = abs(lp) - halfExt + cr;
        // The body and straight sides have an exact distance without a square root.
        float dist = max(q.x, q.y) - cr;
        float inverseLength = 0.0;
        if (min(q.x, q.y) > 0.0) {
            float squaredLength = dot(q, q);
            inverseLength = inversesqrt(max(squaredLength, 0.000001));
            dist = squaredLength * inverseLength - cr;
        }
        if (dist >= AA * 0.5) return half4(0.0);

        // A smooth bevel has no slope discontinuity at its transition into the body.
        float rim = clamp(1.0 + dist * optics.y, 0.0, 1.0);
        float bend = rim * rim * (3.0 - 2.0 * rim);
        float2 n = float2(0.0);
        if (rim > 0.0 && (optics.z > 0.0 || dispersionPx > 0.0 || edge > 0.0)) {
            // The optical bevel may be rounder than the clip, including square corners.
            // Its normal reaches zero only in the flat interior where bending is zero.
            // This avoids the diagonal seam from selecting the nearest rectangle edge.
            float extraRadius = max(optics.x - cr, 0.0);
            float2 normalEdge = max(q + extraRadius, 0.0);
            if (min(normalEdge.x, normalEdge.y) > 0.0) {
                float inverseNormal = extraRadius > 0.0
                    ? inversesqrt(max(dot(normalEdge, normalEdge), 0.000001))
                    : inverseLength;
                n = sign(lp) * normalEdge * inverseNormal;
            } else {
                n = sign(lp) * sign(normalEdge);
            }
        }

        float2 sc = fc - bend * n * optics.z - (lp * lens.zw) * optics.w;
        float2 upperBound = max(halfExt * 2.0 - 0.5, float2(0.5));
        sc = clamp(sc, float2(0.5), upperBound);
        half4 col = content.eval(sc);
        if (dispersionPx > 0.0 && rim > 0.0) {
            // Dispersion belongs to the bending rim; the flat interior needs one sample.
            float2 shift = n * (dispersionPx * bend);
            half4 red = content.eval(clamp(sc - shift, float2(0.5), upperBound));
            half4 blue = content.eval(clamp(sc + shift, float2(0.5), upperBound));
            col = half4(red.r, col.g, blue.b, max(col.a, max(red.a, blue.a)));
        }

        // Child shaders return premultiplied colors. Preserve their coverage through
        // tinting and lighting, including transparent holes and partial source crops.
        col.rgb = mix(col.rgb, tint.rgb * col.a, tint.a);
        if (edge > 0.0 && rim > 0.0) {
            float facing = dot(n, LIGHT_DIRECTION);
            float key = max(facing, 0.0);
            float fill = max(-facing, 0.0);
            key *= key;
            fill *= fill;
            float narrowRim = smoothstep(-edgeWidth, 0.0, dist);
            float reflection = narrowRim * (0.12 + 0.88 * key * key + 0.24 * fill * fill);
            float shoulder = bend * (1.0 - bend);
            half highlight = half(clamp(edge * (reflection + shoulder * 0.12), 0.0, 1.0));
            col.rgb *= half(1.0 - clamp(edge * shoulder * fill * 0.12, 0.0, 1.0));
            col.rgb += (half3(col.a) - col.rgb) * highlight;
        }

        return col * half(1.0 - smoothstep(-AA * 0.5, AA * 0.5, dist));
    }
""".trimIndent()

// =============================================================================
// STACK BLUR (CPU FALLBACK < API 33)
// =============================================================================

private val stackBlurMulTable = intArrayOf(
    512,512,456,512,328,456,335,512,405,328,271,456,388,335,292,512,
    454,405,364,328,298,271,496,456,420,388,360,335,312,292,273,512,
    482,454,428,405,383,364,345,328,312,298,284,271,259,496,475,456,
    437,420,404,388,374,360,347,335,323,312,302,292,282,273,265,512,
    497,482,468,454,441,428,417,405,394,383,373,364,354,345,337,328,
    320,312,305,298,291,284,278,271,265,259,507,496,485,475,465,456,
    446,437,428,420,412,404,396,388,381,374,367,360,354,347,341,335,
    329,323,318,312,307,302,297,292,287,282,278,273,269,265,261,512,
    505,497,489,482,475,468,461,454,447,441,435,428,422,417,411,405,
    399,394,389,383,378,373,368,364,359,354,350,345,341,337,332,328,
    324,320,316,312,309,305,301,298,294,291,287,284,281,278,275,271,
    268,265,262,259,257,507,501,496,491,485,480,475,470,465,460,456,
    451,446,442,437,433,428,424,420,416,412,408,404,400,396,392,388,
    385,381,377,374,370,367,363,360,357,354,350,347,344,341,338,335,
    332,329,326,323,320,318,315,312,310,307,304,302,299,297,294,292,
    289,287,285,282,280,278,275,273,271,269,267,265,263,261,259
)

private val stackBlurShrTable = intArrayOf(
    9,11,12,13,13,14,14,15,15,15,15,16,16,16,16,17,
    17,17,17,17,17,17,18,18,18,18,18,18,18,18,18,19,
    19,19,19,19,19,19,19,19,19,19,19,19,19,20,20,20,
    20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,21,
    21,21,21,21,21,21,21,21,21,21,21,21,21,21,21,21,
    21,21,21,21,21,21,21,21,21,21,22,22,22,22,22,22,
    22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,
    22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,23,
    23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,
    23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,
    23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,23,
    23,23,23,23,23,24,24,24,24,24,24,24,24,24,24,24,
    24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,
    24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,
    24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,24,
    24,24,24,24,24,24,24,24,24,24,24,24,24,24,24
)

/** Scratch storage owned by a capture state or draw cache, never shared by active workers. */
internal class StackBlurWorkspace {
    var pixels = IntArray(0)
        private set
    var nativeScratch = IntArray(0)
        private set
    var red = IntArray(0)
        private set
    var green = IntArray(0)
        private set
    var blue = IntArray(0)
        private set
    var alpha = IntArray(0)
        private set
    var stack = emptyArray<IntArray>()
        private set
    var vmin = IntArray(0)
        private set

    fun prepare(width: Int, height: Int, diameter: Int) {
        val count = width * height
        if (pixels.size < count) pixels = IntArray(count)
        if (red.size < count) {
            red = IntArray(count)
            green = IntArray(count)
            blue = IntArray(count)
            alpha = IntArray(count)
        }
        if (stack.size < diameter) stack = Array(diameter) { IntArray(4) }
        if (vmin.size < maxOf(width, height)) vmin = IntArray(maxOf(width, height))
    }

    fun prepareNative(width: Int, height: Int) {
        val count = width * height
        if (pixels.size < count) pixels = IntArray(count)
        prepareNativeScratch(width, height)
    }

    fun prepareNativeScratch(width: Int, height: Int) {
        val count = width * height
        if (nativeScratch.size < count) nativeScratch = IntArray(count)
    }

    fun clear() {
        pixels = IntArray(0)
        nativeScratch = IntArray(0)
        red = IntArray(0)
        green = IntArray(0)
        blue = IntArray(0)
        alpha = IntArray(0)
        stack = emptyArray()
        vmin = IntArray(0)
    }
}

internal fun applyStackBlur(
    bitmap: Bitmap,
    radius: Int,
    workspace: StackBlurWorkspace = StackBlurWorkspace()
): Bitmap {
    if (radius < 1) return bitmap
    // Clamp the kernel as well as its normalization table index.
    val boundedRadius = radius.coerceAtMost(stackBlurMulTable.lastIndex)
    // A legacy reciprocal (radius 174) can produce 256 between passes. Keep the
    // wider JVM channel planes for that case instead of truncating its pixels.
    val maximumChannel = (255 * (boundedRadius + 1) * (boundedRadius + 1) *
        stackBlurMulTable[boundedRadius]) ushr stackBlurShrTable[boundedRadius]
    if (maximumChannel <= 255 && NativeStackBlur.available) {
        val width = bitmap.width
        val height = bitmap.height
        workspace.prepareNativeScratch(width, height)
        if (NativeStackBlur.applyOpaque(bitmap, workspace.nativeScratch, boundedRadius,
                stackBlurMulTable[boundedRadius], stackBlurShrTable[boundedRadius])) {
            bitmap.setHasAlpha(false)
            return bitmap
        }
        workspace.prepareNative(width, height)
        bitmap.getPixels(workspace.pixels, 0, width, 0, 0, width, height)
        if (NativeStackBlur.apply(workspace.pixels, workspace.nativeScratch, width, height,
                boundedRadius, stackBlurMulTable[boundedRadius], stackBlurShrTable[boundedRadius])) {
            bitmap.setPixels(workspace.pixels, 0, width, 0, 0, width, height)
            return bitmap
        }
    }
    return stackBlur(bitmap, boundedRadius, workspace)
}

internal fun stackBlur(bitmap: Bitmap, radius: Int, workspace: StackBlurWorkspace): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val div = radius + radius + 1
    workspace.prepare(w, h, div)
    val pixels = workspace.pixels
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val r = workspace.red
    val g = workspace.green
    val b = workspace.blue
    val a = workspace.alpha

    val mulSum = stackBlurMulTable[radius]
    val shrSum = stackBlurShrTable[radius]

    val stack = workspace.stack
    val vmin = workspace.vmin

    var rsum: Int; var gsum: Int; var bsum: Int; var asum: Int
    var routsum: Int; var goutsum: Int; var boutsum: Int; var aoutsum: Int
    var rinsum: Int; var ginsum: Int; var binsum: Int; var ainsum: Int
    var stackpointer: Int; var stackstart: Int; var sir: IntArray; var rbs: Int
    val r1 = radius + 1
    var p: Int; var yi: Int; var yw: Int

    yw = 0; yi = 0
    var y = 0
    while (y < h) {
        rsum = 0; gsum = 0; bsum = 0; asum = 0
        routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
        rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0

        var i = -radius
        while (i <= radius) {
            p = pixels[yi + (i.coerceIn(0, wm))]
            sir = stack[i + radius]
            sir[0] = (p ushr 16) and 0xff
            sir[1] = (p ushr 8) and 0xff
            sir[2] = p and 0xff
            sir[3] = p ushr 24
            rbs = r1 - abs(i)
            rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs; asum += sir[3] * rbs
            if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3] }
            else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3] }
            i++
        }
        stackpointer = radius

        var x = 0
        while (x < w) {
            r[yi] = (rsum * mulSum) ushr shrSum
            g[yi] = (gsum * mulSum) ushr shrSum
            b[yi] = (bsum * mulSum) ushr shrSum
            a[yi] = (asum * mulSum) ushr shrSum
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum; asum -= aoutsum
            stackstart = stackpointer - radius + div
            if (stackstart >= div) stackstart -= div
            sir = stack[stackstart]
            routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]
            if (y == 0) vmin[x] = (x + r1).coerceAtMost(wm)
            p = pixels[yw + vmin[x]]
            sir[0] = (p ushr 16) and 0xff; sir[1] = (p ushr 8) and 0xff
            sir[2] = p and 0xff; sir[3] = p ushr 24
            rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
            rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum
            stackpointer++
            if (stackpointer == div) stackpointer = 0
            sir = stack[stackpointer]
            routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
            rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]; ainsum -= sir[3]
            yi++; x++
        }
        yw += w; y++
    }

    var x = 0
    while (x < w) {
        rsum = 0; gsum = 0; bsum = 0; asum = 0
        routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
        rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0
        var yp = -radius * w
        var i = -radius
        while (i <= radius) {
            yi = 0.coerceAtLeast(yp) + x
            sir = stack[i + radius]
            sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]; sir[3] = a[yi]
            rbs = r1 - abs(i)
            rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs; asum += a[yi] * rbs
            if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3] }
            else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3] }
            if (i < hm) yp += w
            i++
        }
        yi = x; stackpointer = radius
        y = 0
        while (y < h) {
            pixels[yi] = ((asum * mulSum) ushr shrSum shl 24) or
                    ((rsum * mulSum) ushr shrSum shl 16) or
                    ((gsum * mulSum) ushr shrSum shl 8) or
                    ((bsum * mulSum) ushr shrSum)
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum; asum -= aoutsum
            stackstart = stackpointer - radius + div
            if (stackstart >= div) stackstart -= div
            sir = stack[stackstart]
            routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]
            if (x == 0) vmin[y] = (y + r1).coerceAtMost(hm) * w
            p = x + vmin[y]
            sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]; sir[3] = a[p]
            rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
            rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum
            stackpointer++
            if (stackpointer == div) stackpointer = 0
            sir = stack[stackpointer]
            routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
            rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]; ainsum -= sir[3]
            yi += w; y++
        }
        x++
    }

    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap
}

internal fun applyCpuGlassRefraction(
    bitmap: Bitmap,
    refraction: Float,
    edge: Float,
    pixels: IntArray,
    output: IntArray,
    maps: CpuRefractionMapCache? = null
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 1 || h <= 1) return bitmap

    val count = w * h
    if (pixels.size < count || output.size < count) return bitmap

    val refractionAmount = refraction.coerceAtLeast(0f)
    val edgeAmount = edge.coerceAtLeast(0f)
    if (refractionAmount <= 0f && edgeAmount <= 0f) return bitmap

    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val indices = maps?.indices(w, h, refractionAmount, edgeAmount)
    if (indices != null) {
        var i = 0
        while (i < count) {
            output[i] = pixels[indices[i]]
            i++
        }
    } else {
        forEachRefractionSample(w, h, refractionAmount, edgeAmount) { index, source ->
            output[index] = pixels[source]
        }
    }
    bitmap.setPixels(output, 0, w, 0, 0, w, h)
    return bitmap
}

/** Bounded per-owner geometry cache; never retains source pixels or shared mutable workspaces. */
internal class CpuRefractionMapCache(
    private val maxPixels: Int = 512 * 1024,
    private val maxEntries: Int = 8
) {
    private data class Key(val width: Int, val height: Int, val refraction: Float, val edge: Float)
    private val maps = LinkedHashMap<Key, IntArray>(8, 0.75f, true)
    private var pixelCount = 0

    fun indices(width: Int, height: Int, refraction: Float, edge: Float): IntArray? {
        val count = width.toLong() * height
        if (count > maxPixels || maxEntries <= 0) return null
        val key = Key(width, height, refraction, edge)
        maps[key]?.let { return it }
        while (maps.isNotEmpty() && (pixelCount + count > maxPixels || maps.size >= maxEntries)) {
            val oldest = maps.entries.iterator()
            pixelCount -= oldest.next().value.size
            oldest.remove()
        }
        return IntArray(count.toInt()).also { indices ->
            forEachRefractionSample(width, height, refraction, edge) { index, source -> indices[index] = source }
            maps[key] = indices
            pixelCount += indices.size
        }
    }

    fun clear() {
        maps.clear()
        pixelCount = 0
    }
}

private inline fun forEachRefractionSample(
    w: Int,
    h: Int,
    refractionAmount: Float,
    edgeAmount: Float,
    sample: (Int, Int) -> Unit
) {
    val halfW = w * 0.5f
    val halfH = h * 0.5f
    val minExt = min(halfW, halfH).coerceAtLeast(1f)
    val edgeWidth = (minExt * (0.10f + edgeAmount * 0.28f) * 1.45f).coerceAtLeast(1f)
    val radialStrength = refractionAmount * minExt * 0.08f
    val edgeStrength = (edgeAmount * 0.22f + refractionAmount * 0.12f) * minExt

    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val idx = y * w + x
            val fx = x + 0.5f
            val fy = y + 0.5f

            val left = fx
            val right = w - fx
            val top = fy
            val bottom = h - fy
            val nearestHorizontal = min(left, right)
            val nearestVertical = min(top, bottom)
            val nearest = min(nearestHorizontal, nearestVertical)

            var nx = 0f
            var ny = 0f
            if (nearestHorizontal <= nearestVertical) {
                nx = if (left <= right) -1f else 1f
            } else {
                ny = if (top <= bottom) -1f else 1f
            }

            val normalizedX = ((fx - halfW) / halfW).coerceIn(-1f, 1f)
            val normalizedY = ((fy - halfH) / halfH).coerceIn(-1f, 1f)
            val radial = (normalizedX * normalizedX + normalizedY * normalizedY).coerceIn(0f, 1f)
            val radialBend = radialStrength * (1f - radial)
            val edgeMask = 1f - cpuSmoothStep(0f, edgeWidth, nearest)
            val edgeBend = edgeStrength * edgeMask * edgeMask

            val sampleX = fx - normalizedX * radialBend - nx * edgeBend
            val sampleY = fy - normalizedY * radialBend - ny * edgeBend
            val sx = sampleX.toInt().coerceIn(0, w - 1)
            val sy = sampleY.toInt().coerceIn(0, h - 1)
            sample(idx, sy * w + sx)

            x++
        }
        y++
    }
}

private fun cpuSmoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
