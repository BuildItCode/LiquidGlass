package com.builditcode.glass.core.helpers

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Paint
import com.builditcode.glass.core.RuntimeShader
import com.builditcode.glass.core.asAndroidRuntimeShader


fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        else null
}

fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.asFrameworkPaint().shader = runtimeShader?.asAndroidRuntimeShader()
}
