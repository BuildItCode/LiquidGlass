package com.builditcode.glass

import android.graphics.RenderNode
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NestedSourceRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun threeSourcesReuseLowerDisplayListsAndKeepLiveOutputsWhenTheBackgroundChanges() {
        val manager = BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 0L)
        lateinit var view: View
        var backgroundDraws = 0
        val backgroundColor = mutableStateOf(Color.Red)
        val glass = BackdropFilter.Glass(blurRadiusIntensity = 2f)
        compose.setContent {
            view = LocalView.current
            QuadLevelLayout(
                manager = manager,
                modifier = Modifier.size(100.dp),
                background = {
                    Box(Modifier.fillMaxSize().drawBehind { backgroundDraws++; drawRect(backgroundColor.value) })
                },
                midground = { Box(Modifier.size(80.dp).layeredBackdropCapture(QuadLevelLayers.Background, filter = glass)) },
                foreground = { Box(Modifier.size(60.dp).layeredBackdropCapture(QuadLevelLayers.Midground, filter = glass.copy(refraction = 0.4f))) },
                overlay = { Box(Modifier.size(40.dp).layeredBackdropCapture(QuadLevelLayers.Foreground, filter = glass.copy(blurRadiusIntensity = 4f))) }
            )
        }
        val sourceNames = listOf(QuadLevelLayers.Background, QuadLevelLayers.Midground, QuadLevelLayers.Foreground)
        val priorResults = HashMap<String, BackdropState.CaptureResult>()
        compose.runOnIdle {
            // Robolectric does not issue window draw frames automatically. Record one
            // explicitly into a native hardware canvas to exercise Modifier.Node.draw.
            val frame = RenderNode("layer-chain-test")
            try {
                frame.setPosition(0, 0, view.width, view.height)
                val canvas = frame.beginRecording(view.width, view.height)
                try { view.draw(canvas) } finally { frame.endRecording() }
                assertEquals("Upper sources reuse the bottom source's retained display list", 2, backgroundDraws)
                for (name in sourceNames) {
                    val state = manager.getState(name)
                    val regions = state.snapshotSoftwareRegions()
                    assertEquals(1, regions.size)
                    val result = state.getResult(regions.single().id)!!
                    assertNotNull("$name must publish its capture", result.masterLayer)
                    priorResults[name] = result
                }
            } finally {
                frame.discardDisplayList()
            }
            backgroundDraws = 0
            backgroundColor.value = Color.Blue
        }
        compose.runOnIdle {
            val frame = RenderNode("updated-layer-chain")
            try {
                frame.setPosition(0, 0, view.width, view.height)
                val canvas = frame.beginRecording(view.width, view.height)
                try { view.draw(canvas) } finally { frame.endRecording() }
                assertEquals(2, backgroundDraws)
                for (name in sourceNames) {
                    val state = manager.getState(name)
                    val result = state.getResult(state.snapshotSoftwareRegions().single().id)!!
                    assertSame("$name must update through its retained GPU output", priorResults[name], result)
                }
            } finally {
                frame.discardDisplayList()
                manager.disposeAll()
            }
        }
    }
}
