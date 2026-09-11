package com.builditcode.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiquidControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sliderUsesLatestCallbacksWithoutRestartingItsPointerHandler() {
        val generation = mutableIntStateOf(0)
        val changes = mutableListOf<Int>()
        val finishes = mutableListOf<Int>()
        compose.setContent {
            val capturedGeneration = generation.intValue
            LiquidSlider(
                value = 0.5f,
                onValueChange = { changes += capturedGeneration },
                onValueChangeFinished = { finishes += capturedGeneration },
                modifier = Modifier.testTag("slider")
            )
        }
        // Material extends slider semantics beyond its visible bounds; use points inside the track.
        compose.onNodeWithTag("slider").performTouchInput { click(Offset(width * 0.15f, centerY)) }
        compose.runOnIdle { generation.intValue = 1 }
        compose.onNodeWithTag("slider").performTouchInput { click(Offset(width * 0.85f, centerY)) }
        compose.runOnIdle {
            assertEquals(listOf(0, 1), changes)
            assertEquals(listOf(0, 1), finishes)
        }
    }

    @Test
    fun cancelledSliderGestureDoesNotReportCompletion() {
        var finishes = 0
        compose.setContent {
            LiquidSlider(
                value = 0.5f,
                onValueChange = {},
                onValueChangeFinished = { finishes++ },
                modifier = Modifier.testTag("slider")
            )
        }
        compose.onNodeWithTag("slider").performTouchInput {
            down(center)
            cancel()
        }
        compose.runOnIdle { assertEquals(0, finishes) }
    }

    @Test
    fun replacingOrRemovingManagerRebindsExistingCaptureNodes() {
        val first = BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 16L, true)
        val second = BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.7f, 16L, true)
        first.stopUpdates()
        second.stopUpdates()
        val manager = mutableStateOf<BackdropLayerManager?>(first)
        compose.setContent {
            CompositionLocalProvider(LocalBackdropLayerManager provides manager.value) {
                Box {
                    Box(Modifier.size(100.dp).layeredBackdropSource("background"))
                    Box(Modifier.size(50.dp).layeredBackdropCapture("background"))
                }
            }
        }
        compose.runOnIdle {
            assertTrue(first.getState("background").pendingCpuBlurRadii().isNotEmpty())
            manager.value = second
        }
        compose.runOnIdle {
            assertTrue(first.getState("background").pendingCpuBlurRadii().isEmpty())
            assertTrue(second.getState("background").pendingCpuBlurRadii().isNotEmpty())
            manager.value = null
        }
        compose.runOnIdle {
            assertTrue(second.getState("background").pendingCpuBlurRadii().isEmpty())
            manager.value = first
        }
        compose.runOnIdle {
            assertTrue(first.getState("background").pendingCpuBlurRadii().isNotEmpty())
        }
    }

    @Test
    fun disablingSearchDuringPressRestoresItsRestingSize() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            LiquidSearchBar("", {}, enabled = enabled.value, modifier = Modifier.testTag("search"))
        }
        val node = compose.onNodeWithTag("search")
        val restingHeight = node.fetchSemanticsNode().boundsInRoot.height
        node.performTouchInput { down(center) }
        compose.runOnIdle { enabled.value = false }
        compose.waitForIdle()
        assertEquals(restingHeight, node.fetchSemanticsNode().boundsInRoot.height, 0.1f)
        node.performTouchInput { cancel() }
    }

    @Test
    fun removingAnOldHandlePreservesItsReplacement() {
        val state = LiquidScaffoldState(BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 16L))
        val old = state.addComponent(key = "popup") { Text("Old popup") }
        state.addComponent(key = "popup") { Text("Replacement") }
        old.remove()
        compose.setContent { state.RenderLayer(QuadLevelLayers.Overlay) }
        compose.onNodeWithText("Replacement").assertExists()
    }

    @Test
    fun automaticKeysCannotReplaceExplicitIntegerKeys() {
        val state = LiquidScaffoldState(BackdropLayerManager(CoroutineScope(Dispatchers.Main), 0.5f, 16L))
        state.addComponent(key = 0) { Text("Explicit") }
        state.addComponent { Text("Automatic") }
        compose.setContent { state.RenderLayer(QuadLevelLayers.Overlay) }
        compose.onNodeWithText("Explicit").assertExists()
        compose.onNodeWithText("Automatic").assertExists()
    }
}
