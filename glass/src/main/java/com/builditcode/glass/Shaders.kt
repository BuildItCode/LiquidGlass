package com.builditcode.glass

import android.graphics.Bitmap
import org.intellij.lang.annotations.Language
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


// =============================================================================
// AGSL GLASS SHADER
// =============================================================================

@Language("AGSL")
internal val GLASS_SHADER = """
    uniform float2 resolution;
    uniform float2 lensCenter;
    uniform float2 lensSize;
    uniform float cornerRadius;
    uniform float refraction;
    uniform float curve;
    uniform float dispersion;
    uniform float saturation;
    uniform float contrast;
    uniform float4 tint;
    uniform float edge;
    uniform shader content;

    const float AA = 1.5;

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

    float luminance(half3 c) { return dot(c, half3(0.2126, 0.7152, 0.0722)); }

    half3 colorGrade(half3 c, float sat, float con, float4 t) {
        float g = luminance(c);
        half3 s = half3(clamp(mix(half3(g), c, sat), 0.0, 1.0));
        half3 r = half3(clamp((s - 0.5) * con + 0.5, 0.0, 1.0));
        return mix(r, half3(t.rgb), t.a);
    }

    half4 main(float2 fc) {
        float2 half_ext = lensSize * 0.5;
        float min_ext = min(half_ext.x, half_ext.y);
        float cr = min(cornerRadius, min_ext);
        float2 lp = fc - lensCenter;
        float dist = sdfRoundedRect(lp, half_ext, cr);
        if (dist > AA) return half4(0.0);

        float2 n = surfaceNormal(lp, half_ext, cr);
        float2 sc = fc;

        if (refraction > 0.0 && curve > 0.0) {
            float depth = clamp(-dist / (min_ext * refraction), 0.0, 1.0);
            float sf = 1.0 - depth;
            float bend = 1.0 - sqrt(1.0 - sf * sf);
            sc = fc - bend * curve * min_ext * n;
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

        col.rgb = colorGrade(col.rgb, saturation, contrast, tint);

        if (edge > 0.0) {
            float rim = smoothstep(-edge * 10.0, 0.0, dist);
            float li = abs(dot(n, normalize(float2(-1.0, -1.0))));
            col.rgb += half3(rim * li * edge);
        }

        return col * (1.0 - smoothstep(-AA * 0.5, AA * 0.5, dist));
    }
""".trimIndent()

// =============================================================================
// STACK BLUR (CPU FALLBACK < API 31)
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

internal fun applyCpuGlassFallback(
    bitmap: Bitmap,
    cornerRadiusPx: Float,
    refraction: Float,
    curve: Float,
    dispersion: Float,
    saturation: Float,
    contrast: Float,
    edge: Float,
    tintRed: Float,
    tintGreen: Float,
    tintBlue: Float,
    tintAlpha: Float
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 1 || h <= 1) return bitmap

    val source = IntArray(w * h)
    val output = IntArray(w * h)
    bitmap.getPixels(source, 0, w, 0, 0, w, h)

    val halfW = w * 0.5f
    val halfH = h * 0.5f
    val minExt = min(halfW, halfH).coerceAtLeast(1f)
    val radius = cornerRadiusPx.coerceIn(0f, minExt)
    val aa = 1.5f
    val invSqrt2 = 0.70710677f

    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val idx = y * w + x
            val fcX = x + 0.5f
            val fcY = y + 0.5f
            val lpX = fcX - halfW
            val lpY = fcY - halfH
            val dist = cpuRoundedRectDistance(lpX, lpY, halfW, halfH, radius)

            if (dist > aa) {
                output[idx] = 0
                x++
                continue
            }

            val normal = cpuGlassNormal(lpX, lpY, halfW, halfH, radius)
            var sampleX = fcX
            var sampleY = fcY

            if (refraction > 0f && curve > 0f) {
                val depth = (-dist / (minExt * refraction)).coerceIn(0f, 1f)
                val surface = 1f - depth
                val bend = 1f - sqrt((1f - surface * surface).coerceAtLeast(0f))
                sampleX = fcX - bend * curve * minExt * normal.x
                sampleY = fcY - bend * curve * minExt * normal.y
            }

            val centerR = cpuSampleChannel(source, w, h, sampleX, sampleY, 0)
            val centerG = cpuSampleChannel(source, w, h, sampleX, sampleY, 1)
            val centerB = cpuSampleChannel(source, w, h, sampleX, sampleY, 2)
            var alpha = cpuSampleChannel(source, w, h, sampleX, sampleY, 3)

            var red = centerR
            var green = centerG
            var blue = centerB

            if (dispersion > 0f) {
                val normX = lpX / halfW
                val normY = lpY / halfH
                val shiftX = dispersion * normX * normX * normX * minExt * 0.1f
                val shiftY = dispersion * normY * normY * normY * minExt * 0.1f
                red = cpuSampleChannel(source, w, h, sampleX - shiftX, sampleY - shiftY, 0)
                blue = cpuSampleChannel(source, w, h, sampleX + shiftX, sampleY + shiftY, 2)
            }

            val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
            red = ((luminance + (red - luminance) * saturation) - 0.5f) * contrast + 0.5f
            green = ((luminance + (green - luminance) * saturation) - 0.5f) * contrast + 0.5f
            blue = ((luminance + (blue - luminance) * saturation) - 0.5f) * contrast + 0.5f

            if (tintAlpha > 0f) {
                red = red * (1f - tintAlpha) + tintRed * tintAlpha
                green = green * (1f - tintAlpha) + tintGreen * tintAlpha
                blue = blue * (1f - tintAlpha) + tintBlue * tintAlpha
            }

            if (edge > 0f) {
                val rim = cpuSmoothStep(-edge * 10f, 0f, dist)
                val light = abs(normal.x * -invSqrt2 + normal.y * -invSqrt2)
                val highlight = rim * light * edge
                red += highlight
                green += highlight
                blue += highlight
            }

            val mask = 1f - cpuSmoothStep(-aa * 0.5f, aa * 0.5f, dist)
            alpha *= mask

            output[idx] = (cpuPackChannel(alpha) shl 24) or
                    (cpuPackChannel(red) shl 16) or
                    (cpuPackChannel(green) shl 8) or
                    cpuPackChannel(blue)

            x++
        }
        y++
    }

    bitmap.setPixels(output, 0, w, 0, 0, w, h)
    return bitmap
}

private data class CpuNormal(val x: Float, val y: Float)

private fun cpuRoundedRectDistance(px: Float, py: Float, halfW: Float, halfH: Float, radius: Float): Float {
    val dx = abs(px) - halfW + radius
    val dy = abs(py) - halfH + radius
    val outsideX = max(dx, 0f)
    val outsideY = max(dy, 0f)
    return sqrt(outsideX * outsideX + outsideY * outsideY) + min(max(dx, dy), 0f) - radius
}

private fun cpuGlassNormal(px: Float, py: Float, halfW: Float, halfH: Float, radius: Float): CpuNormal {
    val dx = abs(px) - halfW + radius
    val dy = abs(py) - halfH + radius
    val sx = if (px >= 0f) 1f else -1f
    val sy = if (py >= 0f) 1f else -1f

    if (max(dx, dy) > 0f) {
        val nx = max(dx, 0f)
        val ny = max(dy, 0f)
        val len = sqrt(nx * nx + ny * ny)
        if (len > 0.0001f) return CpuNormal(sx * nx / len, sy * ny / len)
    }

    return if (dx > dy) CpuNormal(sx, 0f) else CpuNormal(0f, sy)
}

private fun cpuSampleChannel(pixels: IntArray, w: Int, h: Int, x: Float, y: Float, channel: Int): Float {
    val cx = x.coerceIn(0f, (w - 1).toFloat())
    val cy = y.coerceIn(0f, (h - 1).toFloat())
    val x0 = cx.toInt()
    val y0 = cy.toInt()
    val x1 = (x0 + 1).coerceAtMost(w - 1)
    val y1 = (y0 + 1).coerceAtMost(h - 1)
    val tx = cx - x0
    val ty = cy - y0

    val c00 = cpuChannel(pixels[y0 * w + x0], channel)
    val c10 = cpuChannel(pixels[y0 * w + x1], channel)
    val c01 = cpuChannel(pixels[y1 * w + x0], channel)
    val c11 = cpuChannel(pixels[y1 * w + x1], channel)

    val top = c00 + (c10 - c00) * tx
    val bottom = c01 + (c11 - c01) * tx
    return (top + (bottom - top) * ty) / 255f
}

private fun cpuChannel(color: Int, channel: Int): Float =
    when (channel) {
        0 -> ((color ushr 16) and 0xff).toFloat()
        1 -> ((color ushr 8) and 0xff).toFloat()
        2 -> (color and 0xff).toFloat()
        else -> (color ushr 24).toFloat()
    }

private fun cpuSmoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun cpuPackChannel(value: Float): Int =
    (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
