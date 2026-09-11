package com.builditcode.glass

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class LayerChainCaptureTest {
    @Test
    fun snapshotBackendsKeepTheirThrottleWhileLiveGpuCapturesDoNotWait() = runTest {
        val gpu = BackdropState(backgroundScope, 0.5f, 100L)
        val software = BackdropState(backgroundScope, 0.5f, 100L, disableHardwareAcceleration = true)
        try {
            listOf(gpu, software).forEach {
                it.updateSourceRect(Rect(0f, 0f, 100f, 100f))
                it.registerRegion(1, Rect(0f, 0f, 20f, 20f), 0)
                it.beginHardwareCapture(IntSize(50, 50))
            }
            assertTrue(gpu.shouldCapture)
            assertFalse("CPU snapshots must retain their work limit", software.shouldCapture)
        } finally {
            gpu.dispose()
            software.dispose()
        }
    }

    @Test
    fun recordingAnUpperLayerDoesNotRecaptureItsLowerSources() = runTest {
        val manager = BackdropLayerManager(backgroundScope, 0.5f, 0L)
        val bottom = manager.getState("bottom")
        val middle = manager.getState("middle")
        val top = manager.getState("top")
        try {
            listOf(bottom, middle, top).forEach {
                it.updateSourceRect(Rect(0f, 0f, 100f, 100f))
                it.registerRegion(1, Rect(0f, 0f, 20f, 20f), 0)
            }
            drawingBackdropSource(top) {
                assertTrue("Lower sources still capture during the normal visible draw", bottom.shouldCapture)
            }
            recordingBackdropSource(top) {
                assertFalse("An upper recording must reuse lower captures", bottom.shouldCapture)
                assertFalse(middle.shouldCapture)
                recordingBackdropSource(middle) { assertFalse(bottom.shouldCapture) }
            }
            assertTrue(bottom.shouldCapture)
        } finally { manager.disposeAll() }
    }

    @Test
    fun replayingARecentlyCapturedLowerSourceDoesNotScheduleAnotherCapture() = runTest {
        val manager = BackdropLayerManager(backgroundScope, 0.5f, 100L)
        val bottom = manager.getState("bottom")
        val top = manager.getState("top")
        try {
            bottom.updateSourceRect(Rect(0f, 0f, 100f, 100f))
            bottom.registerRegion(1, Rect(0f, 0f, 20f, 20f), 0)
            bottom.beginHardwareCapture(IntSize(50, 50))
            val before = bottom.sourceInvalidator
            recordingBackdropSource(top) { bottom.requestCaptureAfterPendingWork() }
            testScheduler.advanceTimeBy(101)
            testScheduler.runCurrent()
            assertEquals("Synthetic replay is not a background change", before, bottom.sourceInvalidator)
        } finally { manager.disposeAll() }
    }
}
