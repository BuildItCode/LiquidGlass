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
    uniform float2 resolution;
    uniform float2 lensCenter;
    uniform float2 lensSize;
    uniform float4 cornerRadii;
    uniform float refraction;
    uniform float dispersion;
    uniform float edge;
    uniform float4 tint;
    uniform shader content;

    const float AA = 1.5;
    const float REFRACTION_FALLOFF = 1.15;

    float cornerRadiusFor(float2 p, float4 radii) {
        if (p.y < 0.0) {
            return p.x < 0.0 ? radii.x : radii.y;
        }
        return p.x >= 0.0 ? radii.z : radii.w;
    }

    float sdfRoundedRect(float2 p, float2 ext, float r) {
        float2 d = abs(p) - ext + float2(r);
        return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
    }

    float2 surfaceNormal(float2 p, float2 ext, float r) {
        float2 d = abs(p) - ext + float2(r);
        float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
        if (max(d.x, d.y) > 0.0) return s * normalize(max(d, 0.0));
        return d.x > d.y ? float2(s.x, 0.0) : float2(0.0, s.y);
    }

    half4 main(float2 fc) {
        float2 half_ext = lensSize * 0.5;
        float min_ext = min(half_ext.x, half_ext.y);
        float2 lp = fc - lensCenter;
        float cr = min(cornerRadiusFor(lp, cornerRadii), min_ext);
        float dist = sdfRoundedRect(lp, half_ext, cr);
        if (dist > AA) return half4(0.0);

        float2 n = surfaceNormal(lp, half_ext, cr);
        float2 sc = fc;

        if (refraction > 0.0) {
            float depth = clamp(-dist / (min_ext * refraction * REFRACTION_FALLOFF), 0.0, 1.0);
            float sf = 1.0 - depth;
            float bend = sf * sf;
            sc = fc - bend * refraction * min_ext * n;
        }

        half4 col;
        if (dispersion > 0.0) {
            float2 normalizedPos = lp / half_ext;
            float2 shift = dispersion * normalizedPos * normalizedPos * normalizedPos * min_ext * 0.1;
            half4 center = content.eval(sc);
            col = half4(content.eval(sc - shift).r, center.g, content.eval(sc + shift).b, center.a);
        } else {
            col = content.eval(sc);
        }

        if (edge > 0.0) {
            float rim = smoothstep(-edge * 10.0, 0.0, dist);
            float li = abs(dot(n, normalize(float2(-1.0, -1.0))));
            col.rgb += half3(rim * li * edge);
        }

        col.rgb = mix(col.rgb, half3(tint.rgb), half(tint.a));

        return col * (1.0 - smoothstep(-AA * 0.5, AA * 0.5, dist));
    }
""".trimIndent()

// =============================================================================
// STACK BLUR (CPU FALLBACK < API 33)
// =============================================================================

internal fun applyStackBlur(bitmap: Bitmap, radius: Int): Bitmap {
    if (radius < 1) return bitmap

    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val div = radius + radius + 1

    val r = IntArray(w * h)
    val g = IntArray(w * h)
    val b = IntArray(w * h)
    val a = IntArray(w * h)

    val mulTable = intArrayOf(
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
    val shrTable = intArrayOf(
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

    val clampedRadius = radius.coerceAtMost(mulTable.size - 1)
    val mulSum = mulTable[clampedRadius]
    val shrSum = shrTable[clampedRadius]

    val stack = Array(div) { IntArray(4) }
    val vmin = IntArray(w.coerceAtLeast(h))

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
            sir = stack[stackstart % div]
            routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]
            if (y == 0) vmin[x] = (x + r1).coerceAtMost(wm)
            p = pixels[yw + vmin[x]]
            sir[0] = (p ushr 16) and 0xff; sir[1] = (p ushr 8) and 0xff
            sir[2] = p and 0xff; sir[3] = p ushr 24
            rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
            rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum
            stackpointer = (stackpointer + 1) % div
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
            sir = stack[stackstart % div]
            routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]
            if (x == 0) vmin[y] = (y + r1).coerceAtMost(hm) * w
            p = x + vmin[y]
            sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]; sir[3] = a[p]
            rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
            rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum
            stackpointer = (stackpointer + 1) % div
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
    output: IntArray
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
            output[idx] = pixels[sy * w + sx]

            x++
        }
        y++
    }

    bitmap.setPixels(output, 0, w, 0, 0, w, h)
    return bitmap
}

private fun cpuSmoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
