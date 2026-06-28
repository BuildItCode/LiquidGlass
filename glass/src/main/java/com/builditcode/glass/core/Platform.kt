package com.builditcode.glass.core

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

fun isRenderEffectSupported(): Boolean = true

@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
