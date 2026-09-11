package com.builditcode.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.util.IdentityHashMap

/** A recorded Picture can outlive the lower capture whose pixels it references. */
internal object BackdropBitmapReferences {
    private class Reference(var readers: Int = 0, var recycleRequested: Boolean = false)
    private val references = IdentityHashMap<Bitmap, Reference>()

    @Synchronized
    fun retain(bitmap: Bitmap) {
        check(!bitmap.isRecycled) { "Cannot retain a recycled backdrop bitmap" }
        references.getOrPut(bitmap) { Reference() }.readers++
    }

    @Synchronized
    fun release(bitmap: Bitmap) {
        val reference = references[bitmap] ?: return
        if (--reference.readers == 0) {
            references.remove(bitmap)
            if (reference.recycleRequested) recycleNow(bitmap)
        }
    }

    @Synchronized
    fun recycle(bitmap: Bitmap) {
        val reference = references[bitmap]
        if (reference != null) reference.recycleRequested = true else recycleNow(bitmap)
    }

    @Synchronized
    fun isRetained(bitmap: Bitmap): Boolean = references.containsKey(bitmap)

    private fun recycleNow(bitmap: Bitmap) {
        try { if (!bitmap.isRecycled) bitmap.recycle() } catch (_: Exception) { }
    }
}

/** Owns one reference per bitmap, even when several nested nodes draw the same image. */
internal class BackdropBitmapLease : AutoCloseable {
    private val bitmaps = IdentityHashMap<Bitmap, Unit>()
    private var closed = false

    val isEmpty: Boolean get() = bitmaps.isEmpty()

    fun copy(): BackdropBitmapLease = BackdropBitmapLease().also { copy ->
        check(!closed)
        bitmaps.keys.forEach { bitmap ->
            BackdropBitmapReferences.retain(bitmap)
            copy.bitmaps[bitmap] = Unit
        }
    }

    fun retain(image: ImageBitmap) {
        check(!closed)
        val bitmap = image.asAndroidBitmap()
        if (!bitmaps.containsKey(bitmap)) {
            BackdropBitmapReferences.retain(bitmap)
            bitmaps[bitmap] = Unit
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        bitmaps.keys.forEach(BackdropBitmapReferences::release)
        bitmaps.clear()
    }
}

private val recordingBitmapLeases = ThreadLocal<ArrayDeque<BackdropBitmapLease>>()

internal inline fun <T> retainingBackdropBitmaps(lease: BackdropBitmapLease, block: () -> T): T {
    val leases = recordingBitmapLeases.get()
        ?: ArrayDeque<BackdropBitmapLease>().also(recordingBitmapLeases::set)
    leases.addLast(lease)
    return try { block() } finally { leases.removeLast() }
}

internal fun retainBackdropBitmap(image: ImageBitmap) {
    recordingBitmapLeases.get()?.forEach { it.retain(image) }
}
