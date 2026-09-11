package com.builditcode.glass

import android.graphics.Bitmap
import android.graphics.Picture
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class BackdropBitmapLifetimeTest {
    private fun image(): ImageBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(400) { 0xff000000.toInt() or (it * 3779) }, 0, 20, 0, 0, 20, 20)
    }.asImageBitmap()

    private fun record(result: BackdropState.CaptureResult, lease: BackdropBitmapLease): Picture {
        val scope = CanvasDrawScope()
        val content = object : ContentDrawScope, DrawScope by scope { override fun drawContent() {} }
        val picture = Picture()
        val canvas = picture.beginRecording(40, 40)
        try {
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(40f, 40f)) {
                retainingBackdropBitmaps(lease) {
                    content.drawBitmapInCaptureRegion(result.preparedGlass!!.bitmap, result)
                }
            }
        } finally { picture.endRecording() }
        return picture
    }

    private fun Picture.pixels(): IntArray {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        return try {
            android.graphics.Canvas(bitmap).drawPicture(this)
            IntArray(1600).also { bitmap.getPixels(it, 0, 40, 0, 0, 40, 40) }
        } finally { bitmap.recycle() }
    }

    @Test
    fun twoUpperPicturesKeepLowerGlassPixelsAliveAfterTheLowerSourceIsRemoved() = runTest {
        val bottom = BackdropState(backgroundScope, 0.5f, 0L, true)
        val first = BackdropBitmapLease()
        val second = BackdropBitmapLease()
        try {
            bottom.updateSourceRect(Rect(0f, 0f, 40f, 40f))
            bottom.registerRegion(1, Rect(0f, 0f, 40f, 40f), 3, BackdropFilter.Glass().cpuGlassParameters())
            val master = image()
            val session = bottom.beginHardwareCapture(IntSize(20, 20))
            val prepared = bottom.prepareSoftwareCapture(master, bottom.snapshotSoftwareRegions(), session)
            bottom.applyHardwareImageCapture(master, prepared.blurredMasters, session, prepared)
            val result = bottom.getResult(1)!!
            val pixels = result.preparedGlass!!.bitmap.asAndroidBitmap()
            val firstPicture = record(result, first)
            val secondPicture = record(result, second)
            val expected = firstPicture.pixels()
            bottom.clearSourceCapture()
            assertFalse("An upper recording still owns these pixels", pixels.isRecycled)
            assertTrue(result.fallbackBitmap!!.asAndroidBitmap().isRecycled)
            assertArrayEquals(expected, firstPicture.pixels())
            first.close()
            assertFalse(pixels.isRecycled)
            assertArrayEquals(expected, secondPicture.pixels())
            second.close()
            assertTrue(pixels.isRecycled)
        } finally {
            first.close()
            second.close()
            bottom.dispose()
        }
    }

    @Test
    fun cancellingAnUpperCaptureBeforeDispatchReleasesItsLowerBitmapReferences() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val upper = BackdropState(backgroundScope, 0.5f, 0L, true)
        val image = image()
        val lease = BackdropBitmapLease()
        try {
            lease.retain(image)
            image.safeRecycle()
            assertFalse(image.asAndroidBitmap().isRecycled)
            val picture = Picture().apply { beginRecording(40, 40); endRecording() }
            upper.onPictureRecorded(picture, 40, 40, lease)
            upper.clearSourceCapture()
            testScheduler.runCurrent()
            assertTrue(image.asAndroidBitmap().isRecycled)
        } finally {
            upper.dispose()
            lease.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hardwareReadbackOwnsAnIndependentReferenceFromTheRecordedLayer() {
        val image = image()
        val layerLease = BackdropBitmapLease()
        layerLease.retain(image)
        val readbackLease = layerLease.copy()
        try {
            image.safeRecycle()
            layerLease.close()
            assertFalse(image.asAndroidBitmap().isRecycled)
            readbackLease.close()
            assertTrue(image.asAndroidBitmap().isRecycled)
        } finally {
            layerLease.close()
            readbackLease.close()
        }
    }

    @Test
    fun completedUpperCapturePublishesTheRecordedPixelsAndReleasesItsInputs() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val bottom = BackdropState(scope, 0.5f, 0L, true, isUpdateEnabled = { false })
        val upper = BackdropState(scope, 0.5f, 0L, true, isUpdateEnabled = { false })
        val lease = BackdropBitmapLease()
        try {
            val rect = Rect(0f, 0f, 40f, 40f)
            bottom.updateSourceRect(rect)
            bottom.registerRegion(1, rect, 3, BackdropFilter.Glass().cpuGlassParameters())
            upper.updateSourceRect(rect)
            upper.registerRegion(1, rect, 0)
            val master = image()
            val session = bottom.beginHardwareCapture(IntSize(20, 20))
            val prepared = bottom.prepareSoftwareCapture(master, bottom.snapshotSoftwareRegions(), session)
            bottom.applyHardwareImageCapture(master, prepared.blurredMasters, session, prepared)
            val result = bottom.getResult(1)!!
            val input = result.preparedGlass!!.bitmap.asAndroidBitmap()
            val expected = IntArray(400).also { input.getPixels(it, 0, 20, 0, 0, 20, 20) }
            val picture = record(result, lease)
            upper.onPictureRecorded(picture, 40, 40, lease)
            bottom.clearSourceCapture()
            assertFalse(input.isRecycled)
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            val output = upper.getResult(1)!!.fallbackBitmap!!.asAndroidBitmap()
            val actual = IntArray(400).also { output.getPixels(it, 0, 20, 0, 0, 20, 20) }
            assertArrayEquals(expected, actual)
            assertTrue("Completed captures must release their recorded inputs", input.isRecycled)
        } finally {
            bottom.dispose()
            upper.dispose()
            scope.cancel()
            lease.close()
            Dispatchers.resetMain()
        }
    }
}
