package com.builditcode.liquidglass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.builditcode.glass.components.LiquidBottomTab
import com.builditcode.glass.components.LiquidBottomTabs
import com.builditcode.glass.components.LiquidButton
import com.builditcode.glass.components.LiquidCard
import com.builditcode.glass.components.LiquidSearchBar
import com.builditcode.glass.components.LiquidSlider
import com.builditcode.glass.components.LiquidToggle
import com.builditcode.glass.components.glassBorder
import com.builditcode.glass.components.rememberGlassBorderGyroscopeRotation
import com.builditcode.glass.core.effects.adaptiveLuminanceGlass
import com.builditcode.glass.core.effects.blur
import com.builditcode.glass.core.effects.lens
import com.builditcode.glass.core.effects.vibrancy
import com.builditcode.glass.core.highlight.Highlight
import com.builditcode.glass.core.layeredAdaptiveLuminanceBackdropCapture
import com.builditcode.glass.core.layeredBackdropCapture
import com.builditcode.glass.core.layeredBackdropSource
import com.builditcode.glass.core.rememberAdaptiveLuminanceState
import com.builditcode.glass.core.rememberLayeredBackdropOrEmpty
import com.builditcode.glass.core.shapes.RoundedRectangle
import com.builditcode.glass.layout.TriLevelLayout
import com.builditcode.liquidglass.ui.theme.LiquidGlassTheme
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiquidGlassTheme {
                DemoApp()
            }
        }
    }
}

private enum class DemoScreen(val label: String) {
    Playground("Playground"),
    Components("Components")
}

@Composable
private fun DemoApp() {
    var screen by remember { mutableStateOf(DemoScreen.Playground) }

    DemoPreviewScreen(
        screen = screen,
        screenSwitcher = {
            ScreenSwitcher(
                selected = screen,
                onSelected = { screen = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp)
            )
        }
    )
}

@Composable
private fun DemoPreviewScreen(
    screen: DemoScreen,
    screenSwitcher: (@Composable BoxScope.() -> Unit)? = null
) {
    TriLevelLayout(
        modifier = Modifier.fillMaxSize(),
        background = { AnimatedWallpaperBackdrop() },
        overlay = {
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    DemoScreen.Playground -> GlassPlaygroundContent()
                    DemoScreen.Components -> ComponentShowcaseContent()
                }

                screenSwitcher?.invoke(this)
            }
        }
    )
}

@Preview(
    name = "Glass Playground",
    group = "LiquidGlass Screens",
    showBackground = true,
    backgroundColor = 0xFF101318,
    showSystemUi = true
)
@Composable
private fun GlassPlaygroundPreview() {
    LiquidGlassTheme {
        DemoPreviewScreen(screen = DemoScreen.Playground)
    }
}

@Preview(
    name = "Glass Components",
    group = "LiquidGlass Screens",
    showBackground = true,
    backgroundColor = 0xFF101318,
    showSystemUi = true
)
@Composable
private fun GlassComponentsPreview() {
    LiquidGlassTheme {
        DemoPreviewScreen(screen = DemoScreen.Components)
    }
}

@Composable
private fun AnimatedWallpaperBackdrop() {
    val infinite = rememberInfiniteTransition(label = "wallpaper-motion")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2f).toFloat(),
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing)),
        label = "wallpaper-phase"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101318))
    ) {
        Image(
            painter = painterResource(R.drawable.img_test),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.18f
                    scaleY = 1.18f
                    translationX = 42f * sin(t)
                    translationY = 56f * sin(t * 0.72f)
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.08f),
                            Color.Black.copy(alpha = 0.28f)
                        )
                    )
                )
        )
    }
}

@Composable
private fun BoxScope.GlassPlaygroundContent() {
    val scope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoomAnimation = remember { Animatable(1f) }
    val rotationAnimation = remember { Animatable(0f) }

    var cornerRadius by remember { mutableFloatStateOf(0.5f) }
    var blurRadius by remember { mutableFloatStateOf(8f) }
    var refractionHeight by remember { mutableFloatStateOf(0.2f) }
    var refractionAmount by remember { mutableFloatStateOf(0.2f) }
    var chromaticAberration by remember { mutableStateOf(false) }
    val adaptiveLuminanceState = rememberAdaptiveLuminanceState(initialLuminance = 0.5f)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        key(cornerRadius) {
            Box(
                Modifier
                    .padding(top = 86.dp)
                    .statusBarsPadding()
                    .layeredAdaptiveLuminanceBackdropCapture(
                        state = adaptiveLuminanceState,
                        shape = { RoundedRectangle(128.dp * cornerRadius) },
                        effects = {
                            val minDimension = size.minDimension
                            vibrancy()
                            adaptiveLuminanceGlass(
                                lowLuminanceBlurRadius = (blurRadius * 0.25f).dp.toPx(),
                                neutralBlurRadius = blurRadius.dp.toPx(),
                                highLuminanceBlurRadius = (blurRadius * 1.5f).dp.toPx()
                            )
                            lens(
                                refractionHeight = refractionHeight * minDimension * 0.5f,
                                refractionAmount = refractionAmount * minDimension,
                                depthEffect = true,
                                chromaticAberration = chromaticAberration
                            )
                        },
                        highlight = { Highlight.Plain },
                        layerBlock = {
                            val offset = offsetAnimation.value
                            translationX = offset.x
                            translationY = offset.y
                            scaleX = zoomAnimation.value
                            scaleY = zoomAnimation.value
                            rotationZ = rotationAnimation.value
                        },
                        onDrawSurface = {
                            val paneShape = RoundedRectangle(128.dp * cornerRadius)
                            val outline = paneShape.createOutline(size, layoutDirection, this)
                            when (outline) {
                                is Outline.Generic -> {
                                    clipPath(outline.path) {
                                        drawRect(Color.White.copy(alpha = 0.18f))
                                    }
                                }

                                else -> drawOutline(outline, Color.White.copy(alpha = 0.18f))
                            }
                        }
                    )
                    .pointerInput(scope) {
                        fun Offset.rotateBy(angle: Float): Offset {
                            val radians = angle * (PI / 180)
                            val cos = cos(radians)
                            val sin = sin(radians)
                            return Offset(
                                x = (x * cos - y * sin).toFloat(),
                                y = (x * sin + y * cos).toFloat()
                            )
                        }

                        detectTransformGestures { _, pan, gestureZoom, gestureRotate ->
                            val targetRotation = rotationAnimation.value + gestureRotate
                            scope.launch {
                                offsetAnimation.snapTo(
                                    offsetAnimation.value + pan.rotateBy(targetRotation) * zoomAnimation.value
                                )
                                zoomAnimation.snapTo(zoomAnimation.value * gestureZoom)
                                rotationAnimation.snapTo(targetRotation)
                            }
                        }
                    }
                    .size(256.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "luminance\n${"%.2f".format(adaptiveLuminanceState.luminance)}",
                    color = adaptiveLuminanceState.contentColor.value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PlaygroundSlider("Corner radius", cornerRadius, { cornerRadius = it }, 0f..1f)
            PlaygroundSlider("Blur radius", blurRadius, { blurRadius = it }, 0f..32f)
            PlaygroundSlider(
                "Refraction height",
                refractionHeight,
                { refractionHeight = it },
                0f..1f
            )
            PlaygroundSlider(
                "Refraction amount",
                refractionAmount,
                { refractionAmount = it },
                0f..1f
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiquidButton(
                    text = if (chromaticAberration) "Chromatic on" else "Chromatic off",
                    onClick = { chromaticAberration = !chromaticAberration },
                    modifier = Modifier.weight(1f)
                )
                LiquidButton(
                    text = "Reset",
                    onClick = {
                        scope.launch {
                            launch { offsetAnimation.animateTo(Offset.Zero) }
                            launch { zoomAnimation.animateTo(1f) }
                            launch { rotationAnimation.animateTo(0f) }
                        }
                        cornerRadius = 0.5f
                        blurRadius = 8f
                        refractionHeight = 0.2f
                        refractionAmount = 0.2f
                        chromaticAberration = false
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ComponentShowcaseContent() {
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var liked by remember { mutableStateOf(false) }
    var intensity by remember { mutableFloatStateOf(0.62f) }
    var showGlassSheet by remember { mutableStateOf(false) }
    val backdrop = rememberLayeredBackdropOrEmpty()
    val borderRotation = rememberGlassBorderGyroscopeRotation()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layeredBackdropSource(ComponentShowcaseLayer)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 112.dp,
                    bottom = 132.dp,
                    start = 20.dp,
                    end = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = "Component showcase",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Buttons, search, cards, and a liquid bottom navigation over a live backdrop.",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                item {
                    LiquidButton(
                        text = if (showGlassSheet) "Glass sheet open" else "Open glass sheet",
                        onClick = { showGlassSheet = true },
                        borderRotationDegrees = borderRotation,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    LiquidSearchBar(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search components",
                        borderRotationDegrees = borderRotation,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LiquidButton(
                            text = if (liked) "Selected" else "Primary",
                            onClick = { liked = !liked },
                            borderRotationDegrees = borderRotation,
                            modifier = Modifier.weight(1f)
                        )
                        LiquidButton(
                            text = "Secondary",
                            onClick = { selectedTab = (selectedTab + 1) % ShowcaseTabs.size },
                            borderRotationDegrees = borderRotation,
                            modifier = Modifier.weight(1f)
                        )
                        LiquidToggle(
                            selected = { liked },
                            onSelect = { liked = it },
                            backdrop = backdrop,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }

                item {
                    LiquidCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        borderRotationDegrees = borderRotation
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Intensity",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(intensity * 100).toInt()}%",
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 13.sp
                                )
                            }
                            LiquidSlider(
                                value = { intensity },
                                onValueChange = { intensity = it },
                                valueRange = 0f..1f,
                                visibilityThreshold = 0.001f,
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    LiquidCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(30.dp),
                        borderRotationDegrees = borderRotation,
                        onClick = { liked = !liked }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = ShowcaseTabs[selectedTab],
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "This card is rendered through the same named-layer capture path used by the controls.",
                                color = Color.White.copy(alpha = 0.78f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            LiquidButton(
                                text = "Card action",
                                onClick = { liked = !liked },
                                borderRotationDegrees = borderRotation,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    LiquidCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        borderRotationDegrees = borderRotation
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Bottom navigation",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Tap the bar below",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp
                                )
                            }
                            Text(
                                text = ShowcaseTabs[selectedTab],
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                LiquidBottomTabs(
                    selectedTabIndex = { selectedTab },
                    onTabSelected = { selectedTab = it },
                    backdrop = backdrop,
                    tabsCount = ShowcaseTabs.size,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    ShowcaseTabs.forEachIndexed { index, label ->
                        LiquidBottomTab(
                            onClick = { selectedTab = index }
                        ) {
                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (index == selectedTab) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (showGlassSheet) {
            DraggableGlassSheet(
                layerName = ComponentShowcaseLayer,
                onDismiss = { showGlassSheet = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            )
        }
    }
}

@Composable
private fun DraggableGlassSheet(
    layerName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val shape = RoundedRectangle(32.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                translationY = offsetY.value
            }
            .pointerInput(scope) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        scope.launch {
                            offsetY.snapTo((offsetY.value + dragAmount).coerceAtLeast(0f))
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (offsetY.value > 160f) {
                                offsetY.animateTo(520f, tween(180))
                                onDismiss()
                            } else {
                                offsetY.animateTo(0f, tween(220))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            offsetY.animateTo(0f, tween(220))
                        }
                    }
                )
            }
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .layeredBackdropCapture(
                layerName = layerName,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(12.dp.toPx())
                    lens(
                        refractionHeight = 18.dp.toPx(),
                        refractionAmount = 32.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = true
                    )
                },
                highlight = { Highlight.Plain },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.16f))
                }
            )
            .glassBorder(shape, Color.White, 1.dp)
            .padding(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color.White.copy(alpha = 0.56f))
            )
            Text(
                text = "Glass over glass",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Controls and bottom tabs below",
                color = Color.White.copy(alpha = 0.76f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            LiquidButton(
                text = "Close",
                onClick = onDismiss,
                layerName = layerName,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private val ShowcaseTabs = listOf("Home", "Explore", "Library", "Profile")
private const val ComponentShowcaseLayer = "component-showcase"

@Composable
private fun PlaygroundSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Medium)
            Text(
                text = "%.2f".format(value),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f)
            )
        )
    }
}

@Composable
private fun ScreenSwitcher(
    selected: DemoScreen,
    onSelected: (DemoScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .height(52.dp)
            .layeredBackdropCapture(
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(6.dp.toPx(), 14.dp.toPx())
                },
                highlight = { Highlight.Plain },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.14f))
                }
            )
            .glassBorder(shape, Color.White, 1.dp)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DemoScreen.entries.forEach { screen ->
            val isSelected = screen == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                    .clickable { onSelected(screen) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = screen.label,
                    color = Color.White,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}
