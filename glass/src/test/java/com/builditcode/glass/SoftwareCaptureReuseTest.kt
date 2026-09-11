package com.builditcode.glass

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Picture
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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
class SoftwareCaptureReuseTest {
    @Test
    fun identicalPictureFramesKeepResultsButChangedPixelsAndBlurRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val state = BackdropState(scope, 0.5f, 0L, true, isUpdateEnabled = { false })
        suspend fun capture(color: Int) {
            val picture = Picture().apply { beginRecording(100, 100).drawColor(color); endRecording() }
            state.onPictureRecorded(picture, 100, 100)
            scope.coroutineContext[Job]!!.children.toList().joinAll()
        }
        try {
            state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
            state.registerRegion(1, Rect(10f, 10f, 50f, 50f), 3, BackdropFilter.Glass().cpuGlassParameters())
            capture(Color.RED)
            val first = state.getResult(1)!!
            repeat(3) { capture(Color.RED); assertSame(first, state.getResult(1)) }
            state.registerRegion(1, Rect(30f, 30f, 70f, 70f), 3, BackdropFilter.Glass().cpuGlassParameters())
            val moved = state.getResult(1)!!
            assertNotSame(first, moved)
            capture(Color.RED)
            assertSame(moved, state.getResult(1))
            state.updateRegionBlurRadius(1, 7)
            capture(Color.RED)
            assertEquals(7, state.getResult(1)!!.fallbackBlurRadiusPx)
            capture(Color.BLUE)
            assertEquals(Color.BLUE, state.getResult(1)!!.fallbackBitmap!!.asAndroidBitmap().getPixel(5, 5))
        } finally { state.dispose(); scope.cancel(); Dispatchers.resetMain() }
    }

    @Test
    fun comparisonRequiresMatchingGeometryAndPreparedRadiiAndSurvivesSourceRemoval() = runTest {
        val state = BackdropState(backgroundScope, 0.5f, 0L, true)
        val source = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.asImageBitmap()
        val copy = source.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true).asImageBitmap()
        state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
        state.registerRegion(1, Rect(10f, 10f, 50f, 50f), 3)
        val session = state.beginHardwareCapture(IntSize(50, 50))
        val prepared = state.prepareSoftwareCapture(source, state.snapshotSoftwareRegions(), session)
        state.applyHardwareImageCapture(source, prepared.blurredMasters, session, prepared)
        val reference = state.softwareCaptureReference(session, state.snapshotSoftwareRegions())!!
        try {
            assertTrue(reference.matches(copy))
            copy.asAndroidBitmap().setPixel(49, 49, Color.BLUE)
            assertFalse("A single changed pixel must trigger processing", reference.matches(copy))
            assertNull(state.softwareCaptureReference(session.copy(scaledW = 49), state.snapshotSoftwareRegions()))
            assertNull(state.softwareCaptureReference(session.copy(sourceRect = Rect(1f, 0f, 101f, 100f)), state.snapshotSoftwareRegions()))
            state.updateRegionBlurRadius(1, 7)
            assertNull(state.softwareCaptureReference(session, state.snapshotSoftwareRegions()))
            state.clearSourceCapture()
            assertFalse(source.asAndroidBitmap().isRecycled)
            reference.close()
            assertTrue(source.asAndroidBitmap().isRecycled)
        } finally { reference.close(); copy.safeRecycle(); state.dispose() }
    }

    @Test
    fun cancellationBeforeDispatchReleasesTheComparisonReference() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val state = BackdropState(backgroundScope, 0.5f, 0L, true)
        val image = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).asImageBitmap()
        try {
            state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
            state.registerRegion(1, Rect(10f, 10f, 50f, 50f), 0)
            state.applyHardwareImageCapture(image, emptyMap(), state.beginHardwareCapture(IntSize(50, 50)))
            state.onPictureRecorded(Picture().apply { beginRecording(100, 100); endRecording() }, 100, 100)
            state.clearSourceCapture()
            testScheduler.runCurrent()
            assertTrue(image.asAndroidBitmap().isRecycled)
        } finally { state.dispose(); Dispatchers.resetMain() }
    }
}
