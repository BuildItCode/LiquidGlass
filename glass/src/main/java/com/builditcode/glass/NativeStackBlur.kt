package com.builditcode.glass

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.Keep

/** Optional acceleration of the same integer kernel; the JVM implementation is the fallback. */
@Keep
internal object NativeStackBlur {
    val available: Boolean = try {
        System.loadLibrary("glass_blur")
        true
    } catch (_: LinkageError) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun apply(
        pixels: IntArray,
        scratch: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        multiplier: Int,
        shift: Int
    ): Boolean = available && blur(pixels, scratch, width, height, radius, multiplier, shift)

    fun applyOpaque(bitmap: Bitmap, scratch: IntArray, radius: Int, multiplier: Int, shift: Int): Boolean =
        available && bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888 &&
            (Build.VERSION.SDK_INT < 26 || bitmap.colorSpace?.isSrgb == true) &&
            blurOpaque(bitmap, scratch, radius, multiplier, shift)

    private external fun blurOpaque(
        bitmap: Bitmap, scratch: IntArray, radius: Int, multiplier: Int, shift: Int
    ): Boolean

    private external fun blur(
        pixels: IntArray,
        scratch: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        multiplier: Int,
        shift: Int
    ): Boolean
}
