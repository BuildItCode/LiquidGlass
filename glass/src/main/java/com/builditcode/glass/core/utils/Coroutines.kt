package com.builditcode.glass.core.utils

import kotlinx.coroutines.android.awaitFrame as awaitAndroidFrame

suspend fun awaitFrame() {
    awaitAndroidFrame()
}
