package com.builditcode.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.builditcode.glass.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LiquidComponentInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun buttonPressDoesNotRemeasureItsLayout() {
        var measurements = 0
        compose.setContent {
            LiquidButton("Continue", {}, Modifier.width(240.dp).testTag("button").layout { measurable, constraints ->
                measurements++
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.place(0, 0) }
            })
        }
        compose.waitForIdle()
        val initial = measurements
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("button").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        assertEquals("Press feedback must not resize the button or surrounding content", initial, measurements)
        compose.onNodeWithTag("button").performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun sliderThumbTracksTheFingerWithoutWaitingForASpring() {
        val value = mutableFloatStateOf(0.5f)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
                LiquidSlider(value.floatValue, { value.floatValue = it }, Modifier.width(280.dp).testTag("slider"))
            }
        }
        compose.mainClock.autoAdvance = false
        val slider = compose.onNodeWithTag("slider")
        slider.performTouchInput { down(center); moveTo(Offset(width * 0.82f, centerY), 32) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(value.floatValue > 0.75f)
        val image = slider.captureToImage().toPixelMap()
        val white = (0 until image.width).filter { x ->
            val pixel = image[x, image.height / 2]
            pixel.red > 0.9f && pixel.green > 0.9f && pixel.blue > 0.9f
        }
        assertTrue("Thumb center=${white.average()} / ${image.width}, value=${value.floatValue}",
            white.isNotEmpty() && white.average() > image.width * 0.72)
        slider.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun sliderSupportsRtlTouchDirection() {
        val value = mutableFloatStateOf(0.5f)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.padding(24.dp)) {
                    LiquidSlider(value.floatValue, { value.floatValue = it }, Modifier.width(280.dp).testTag("slider"))
                }
            }
        }
        compose.onNodeWithTag("slider").performTouchInput { click(Offset(width * 0.15f, centerY)) }
        compose.runOnIdle { assertTrue(value.floatValue > 0.8f) }
    }

    @Test fun verticalSwipeOverTheSliderScrollsItsParentWithoutChangingTheValue() {
        val value = mutableFloatStateOf(0.5f)
        var scrollOffset = { 0 }
        compose.setContent {
            val scroll = rememberScrollState()
            scrollOffset = { scroll.value }
            Column(Modifier.size(320.dp, 240.dp).verticalScroll(scroll)) {
                LiquidSlider(value.floatValue, { value.floatValue = it }, Modifier.padding(horizontal = 24.dp).testTag("slider"))
                Spacer(Modifier.height(1000.dp))
            }
        }
        compose.onNodeWithTag("slider").performTouchInput {
            down(center)
            moveTo(Offset(centerX, centerY - 180f), 160)
            up()
        }
        compose.runOnIdle { assertTrue(scrollOffset() > 0); assertEquals(0.5f, value.floatValue, 0.001f) }
    }

    @Test fun sliderAcceptsAccessibleStepsAndKeyboardInput() {
        val value = mutableFloatStateOf(0.5f)
        var finishes = 0
        compose.setContent {
            MaterialTheme {
                LiquidSlider(value.floatValue, { value.floatValue = it }, Modifier.width(280.dp).testTag("slider"),
                    steps = 3, onValueChangeFinished = { finishes++ })
            }
        }
        val slider = compose.onNodeWithTag("slider")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.68f) }
        compose.runOnIdle { assertEquals(0.75f, value.floatValue, 0.001f); assertEquals(1, finishes) }
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(0.5f, value.floatValue, 0.001f); assertEquals(2, finishes) }
    }

    @Test fun switchHasAFullTouchTargetAndHonorsDisabledState() {
        val enabled = mutableStateOf(true)
        val checked = mutableStateOf(false)
        compose.setContent { LiquidToggle(checked.value, { checked.value = it }, Modifier.testTag("toggle"), enabled = enabled.value) }
        val toggle = compose.onNodeWithTag("toggle")
        toggle.assertHeightIsAtLeast(48.dp)
        toggle.performTouchInput { click(Offset(centerX, 1f)) }
        compose.runOnIdle { assertTrue(checked.value); enabled.value = false }
        toggle.performTouchInput { click() }
        compose.runOnIdle { assertTrue(checked.value) }
    }

    @Test fun accessibleSliderAdjustmentsSnapInNegativeRanges() {
        val value = mutableFloatStateOf(0f)
        compose.setContent {
            LiquidSlider(value.floatValue, { value.floatValue = it }, Modifier.width(280.dp).testTag("slider"),
                valueRange = -1f..1f, steps = 3)
        }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(-0.3f) }
        compose.runOnIdle { assertEquals(-0.5f, value.floatValue, 0.001f) }
    }

    @Test fun searchSupportsSubmissionAndAnAccessibleClearAction() {
        val value = mutableStateOf("")
        var submitted = ""
        compose.setContent {
            LiquidSearchBar(value.value, { value.value = it }, Modifier.width(300.dp), onSearch = { submitted = it })
        }
        compose.onNode(hasSetTextAction()).performTextInput("Ocean")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals("Ocean", submitted) }
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle { assertEquals("", value.value) }
    }

    @Test fun largerTextCanGrowTheButtonAndSearchField() {
        val fontScale = mutableFloatStateOf(1f)
        val query = mutableStateOf("Ocean")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale.floatValue)) {
                Column {
                    LiquidButton("Continue", {}, Modifier.width(280.dp).testTag("button"))
                    LiquidSearchBar(query.value, {}, Modifier.width(300.dp).testTag("search"))
                }
            }
        }
        val button = compose.onNodeWithTag("button")
        val search = compose.onNodeWithTag("search")
        val buttonHeight = button.fetchSemanticsNode().boundsInRoot.height
        val searchHeight = search.fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { fontScale.floatValue = 2f }
        assertTrue(button.fetchSemanticsNode().boundsInRoot.height > buttonHeight)
        val filledHeight = search.fetchSemanticsNode().boundsInRoot.height
        assertTrue(filledHeight > searchHeight)
        compose.runOnIdle { query.value = "" }
        assertEquals("Clearing the field must not move neighboring controls", filledHeight,
            search.fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun glassBorderRespectsTransparencyAndClipsItsOverlayToTheShape() {
        compose.setContent {
            Row {
                Box(Modifier.size(80.dp).background(Color.Black).testTag("transparent")
                    .glassBorder(RectangleShape, Color.Transparent, 4.dp))
                Box(Modifier.size(80.dp).background(Color.Black).testTag("overlay")
                    .glassBorder(RoundedCornerShape(24.dp), Color.Transparent, 1.dp, overlayBrush = SolidColor(Color.White)))
            }
        }
        val transparent = compose.onNodeWithTag("transparent").captureToImage().toPixelMap()
        assertTrue((0 until transparent.height).all { y -> transparent[1, y].red < 0.02f })
        val overlay = compose.onNodeWithTag("overlay").captureToImage().toPixelMap()
        assertTrue(overlay[0, 0].red < 0.02f)
        assertTrue(overlay[overlay.width / 2, overlay.height / 2].red > 0.98f)
    }
}
