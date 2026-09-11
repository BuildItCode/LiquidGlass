package com.builditcode.glass

import android.graphics.RenderNode
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceIsolationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun redrawingAnOverlayDoesNotRerecordAnUnchangedSource() {
        val manager = BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 0L)
        val overlay = mutableStateOf(Color.White)
        var backgroundDraws = 0
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalBackdropLayerManager provides manager) {
                Box(Modifier.size(100.dp)) {
                    Box(Modifier.size(100.dp).layeredBackdropSource("background").drawBehind {
                        backgroundDraws++
                        drawRect(Color.Red)
                    })
                    Box(Modifier.size(30.dp).layeredBackdropCapture("background"))
                    Box(Modifier.size(10.dp).drawBehind { drawRect(overlay.value) })
                }
            }
        }
        compose.runOnIdle { drawFrame(view) }
        compose.runOnIdle {
            drawFrame(view)
            backgroundDraws = 0
            overlay.value = Color.Blue
        }
        compose.runOnIdle {
            drawFrame(view)
            assertEquals("Unrelated overlay draws must not record the source again", 0, backgroundDraws)
            manager.disposeAll()
        }
    }

    @Test
    fun liveGpuSourceUpdatesDuringTheSnapshotDebounceWindow() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val manager = BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 60_000L)
        val background = mutableStateOf(Color.Red)
        var backgroundDraws = 0
        lateinit var view: View
        var initial: BackdropState.CaptureResult? = null
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalBackdropLayerManager provides manager) {
                Box(Modifier.size(100.dp)) {
                    Box(Modifier.size(100.dp).layeredBackdropSource("background").drawBehind {
                        backgroundDraws++
                        drawRect(background.value)
                    })
                    Box(Modifier.size(30.dp).layeredBackdropCapture("background"))
                }
            }
        }
        val state = manager.getState("background")
        compose.runOnIdle {
            drawFrame(view)
            initial = state.getResult(state.snapshotSoftwareRegions().single().id)
            assertNotNull(initial?.masterLayer)
            backgroundDraws = 0
            background.value = Color.Blue
        }
        compose.runOnIdle {
            drawFrame(view)
            val updated = state.getResult(state.snapshotSoftwareRegions().single().id)!!
            assertEquals("Source changes must draw and record within the snapshot interval", 2, backgroundDraws)
            assertSame("A source update must reach retained consumers without swapping their layer reference",
                initial!!.masterLayer, updated.masterLayer)
            manager.disposeAll()
        }
    }

    @Test
    fun movingGlassUpdatesItsCropInTheLayoutFrame() {
        val manager = BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 0L)
        val position = mutableIntStateOf(0)
        var targetDraws = 0
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalBackdropLayerManager provides manager) {
                Box(Modifier.size(100.dp)) {
                    Box(Modifier.size(100.dp).layeredBackdropSource("background").drawBehind {
                        drawRect(Color.Red)
                    })
                    Box(Modifier.offset { IntOffset(0, position.intValue) }.size(30.dp)
                        .layeredBackdropCapture("background", filter = BackdropFilter.Glass())
                        .drawBehind { targetDraws++ })
                }
            }
        }
        compose.runOnIdle { drawFrame(view) }
        compose.runOnIdle {
            drawFrame(view)
            targetDraws = 0
            position.intValue = 10
            Snapshot.sendApplyNotifications()
            // Placement and crop publication happen inside this draw. There is no
            // intervening snapshot notification to schedule a second frame.
            drawFrame(view)
            assertTrue("A moved glass crop must draw in the same frame as placement", targetDraws > 0)
            manager.disposeAll()
        }
    }

    @Test
    fun scrollingGlassDoesNotRerecordAnUnchangedBackground() {
        val manager = BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 0L)
        val position = mutableIntStateOf(0)
        val background = mutableStateOf(Color.Red)
        var backgroundDraws = 0
        lateinit var view: View
        var initial: BackdropState.CaptureResult? = null
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalBackdropLayerManager provides manager) {
                Box(Modifier.size(100.dp)) {
                    Box(Modifier.size(100.dp).layeredBackdropSource("background").drawBehind {
                        backgroundDraws++
                        drawRect(background.value)
                    })
                    Box(Modifier.offset { IntOffset(0, position.intValue) }.size(30.dp)
                        .layeredBackdropCapture("background", filter = BackdropFilter.Glass()))
                }
            }
        }
        val state = manager.getState("background")
        compose.runOnIdle {
            drawFrame(view)
            initial = state.getResult(state.snapshotSoftwareRegions().single().id)
            assertNotNull(initial?.masterLayer)
            backgroundDraws = 0
            position.intValue = 10
        }
        compose.runOnIdle {
            drawFrame(view)
            val moved = state.getResult(state.snapshotSoftwareRegions().single().id)!!
            assertEquals("Scrolling must reuse the source's display list", 0, backgroundDraws)
            assertSame(initial!!.masterLayer, moved.masterLayer)
            assertNotEquals(initial!!.srcOffset, moved.srcOffset)
            background.value = Color.Blue
        }
        compose.runOnIdle {
            drawFrame(view)
            assertTrue("Real background changes must still render", backgroundDraws > 0)
            manager.disposeAll()
        }
    }

    private fun drawFrame(view: View) {
        val frame = RenderNode("source-isolation")
        try {
            frame.setPosition(0, 0, view.width, view.height)
            val canvas = frame.beginRecording(view.width, view.height)
            try { view.draw(canvas) } finally { frame.endRecording() }
        } finally { frame.discardDisplayList() }
    }
}
