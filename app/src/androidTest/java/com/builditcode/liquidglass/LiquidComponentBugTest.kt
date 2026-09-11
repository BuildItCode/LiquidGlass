package com.builditcode.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.builditcode.glass.LiquidSearchBar
import com.builditcode.glass.LiquidSlider
import com.builditcode.glass.LiquidToggle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidComponentBugTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledSliderRejectsAccessibleChanges() {
        val enabled = mutableStateOf(true)
        val value = mutableFloatStateOf(0.5f)
        var changes = 0
        var finishes = 0
        compose.setContent {
            LiquidSlider(value.floatValue, { value.floatValue = it; changes++ },
                Modifier.width(280.dp).testTag("slider"), enabled = enabled.value,
                onValueChangeFinished = { finishes++ })
        }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) {
            assertFalse("Disabled controls must reject progress actions", it(0.8f))
        }
        compose.runOnIdle {
            assertEquals(0.5f, value.floatValue, 0f)
            assertEquals(0, changes)
            assertEquals(0, finishes)
        }
    }

    @Test fun accessibleSliderDoesNotReportChangesWithinTheCurrentNegativeStep() {
        val value = mutableFloatStateOf(-0.5f)
        var changes = 0
        var finishes = 0
        compose.setContent {
            LiquidSlider(value.floatValue, { value.floatValue = it; changes++ },
                Modifier.width(280.dp).testTag("slider"), valueRange = -1f..1f, steps = 3,
                onValueChangeFinished = { finishes++ })
        }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) {
            assertFalse("Snapping to the current step is not a change", it(-0.4f))
        }
        compose.runOnIdle {
            assertEquals(-0.5f, value.floatValue, 0f)
            assertEquals(0, changes)
            assertEquals(0, finishes)
        }
    }

    @Test fun cancelledSliderDragDoesNotCommitAndTheNextGestureCanScroll() {
        val value = mutableFloatStateOf(0.5f)
        var finishes = 0
        var scrollOffset = { 0 }
        compose.setContent {
            val scroll = rememberScrollState()
            scrollOffset = { scroll.value }
            Column(Modifier.size(320.dp, 240.dp).verticalScroll(scroll)) {
                LiquidSlider(value.floatValue, { value.floatValue = it },
                    Modifier.padding(horizontal = 24.dp).testTag("slider"),
                    onValueChangeFinished = { finishes++ })
                Spacer(Modifier.height(1000.dp))
            }
        }
        val slider = compose.onNodeWithTag("slider")
        slider.performTouchInput {
            down(center)
            moveTo(Offset(width * 0.75f, centerY), 80)
        }
        compose.runOnIdle { assertTrue(value.floatValue > 0.6f) }
        slider.performTouchInput { cancel() }
        compose.runOnIdle { assertEquals("A canceled drag is not a completed edit", 0, finishes) }
        val afterCancel = value.floatValue
        slider.performTouchInput {
            down(center)
            moveTo(Offset(centerX, centerY - 180f), 160)
            up()
        }
        compose.runOnIdle {
            assertTrue(scrollOffset() > 0)
            assertEquals(afterCancel, value.floatValue, 0.001f)
        }
    }

    @Test fun disablingSliderDuringDragDoesNotCommit() {
        val enabled = mutableStateOf(true)
        var finishes = 0
        compose.setContent {
            LiquidSlider(0.5f, {}, Modifier.padding(24.dp).width(280.dp).testTag("slider"),
                enabled = enabled.value, onValueChangeFinished = { finishes++ })
        }
        val slider = compose.onNodeWithTag("slider")
        slider.performTouchInput { down(center); moveTo(Offset(width * 0.75f, centerY), 80) }
        compose.runOnIdle { enabled.value = false }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, finishes) }
    }

    @Test fun disabledSearchRejectsPendingImeSubmission() {
        val enabled = mutableStateOf(true)
        var submissions = 0
        compose.setContent {
            LiquidSearchBar("Ocean", {}, Modifier.width(300.dp), enabled = enabled.value,
                onSearch = { submissions++ })
        }
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        compose.runOnIdle { enabled.value = false }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnImeAction))
            .performSemanticsAction(SemanticsActions.OnImeAction) { it() }
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun sliderCanCompleteATapAfterACanceledDragAndAnEnableCycle() {
        val enabled = mutableStateOf(true)
        val value = mutableFloatStateOf(0.5f)
        var finishes = 0
        compose.setContent {
            LiquidSlider(value.floatValue, { value.floatValue = it },
                Modifier.padding(24.dp).width(280.dp).testTag("slider"), enabled = enabled.value,
                onValueChangeFinished = { finishes++ })
        }
        val slider = compose.onNodeWithTag("slider")
        slider.performTouchInput { down(center); moveTo(Offset(width * 0.75f, centerY), 80) }
        slider.performTouchInput { cancel() }
        slider.performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.runOnIdle { assertEquals(1, finishes); assertTrue(value.floatValue < 0.4f) }
        slider.performTouchInput { down(center); moveTo(Offset(width * 0.75f, centerY), 80) }
        compose.runOnIdle { enabled.value = false }
        slider.performTouchInput { up() }
        compose.runOnIdle { enabled.value = true }
        slider.performTouchInput { click(Offset(width * 0.85f, centerY)) }
        compose.runOnIdle { assertEquals(2, finishes); assertTrue(value.floatValue > 0.8f) }
    }

    @Test fun constrainedSwitchKeepsTheWholeThumbInsideItsTrackInBothDirections() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
                    LiquidToggle(true, {}, Modifier.width(48.dp).testTag("toggle"))
                }
            }
        }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction.value = layoutDirection }
            val image = compose.onNodeWithTag("toggle").captureToImage().toPixelMap()
            val white = (0 until image.width).filter { x ->
                val color = image[x, image.height / 2]
                color.red > 0.95f && color.green > 0.95f && color.blue > 0.95f
            }
            assertTrue("The 32dp thumb should fit fully in a 48dp switch ($layoutDirection): ${white.size}/${image.width}",
                white.size >= image.width * 0.62f)
            assertTrue("The thumb must retain an inset from both edges ($layoutDirection)",
                white.first() > 0 && white.last() < image.width - 1)
        }
    }

    @Test fun disablingAndReenablingDuringADragRestoresTheRestingThumb() {
        val enabled = mutableStateOf(true)
        val value = mutableFloatStateOf(0.5f)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
                LiquidSlider(value.floatValue, { value.floatValue = it },
                    Modifier.width(280.dp).testTag("slider"), enabled = enabled.value)
            }
        }
        val slider = compose.onNodeWithTag("slider")
        fun thumbWidth(): Int {
            val image = slider.captureToImage().toPixelMap()
            return (0 until image.width).count { x ->
                val color = image[x, image.height / 2]
                color.red > 0.95f && color.green > 0.95f && color.blue > 0.95f
            }
        }
        val restingWidth = thumbWidth()
        slider.performTouchInput { down(center); moveTo(Offset(width * 0.75f, centerY), 80) }
        compose.runOnIdle { enabled.value = false }
        slider.performTouchInput { up() }
        compose.runOnIdle { enabled.value = true }
        assertEquals("Disabling during drag must not leave the handle stretched", restingWidth.toFloat(), thumbWidth().toFloat(), 2f)
    }
}
