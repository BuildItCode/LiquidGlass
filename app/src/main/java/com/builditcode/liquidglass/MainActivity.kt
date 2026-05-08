package com.builditcode.liquidglass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.builditcode.glass.BackdropFilter
import com.builditcode.glass.TriLevelLayout
import com.builditcode.glass.glassBorder
import com.builditcode.glass.layeredBackdropCapture
import com.builditcode.glass.rememberBackdropManager
import com.builditcode.liquidglass.ui.theme.LiquidGlassTheme
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiquidGlassTheme {
                VerificationApp()
            }
        }
    }
}

private enum class VerificationScenario(
    val tabLabel: String,
    val title: String,
    val caption: String
) {
    InitialImage(
        tabLabel = "Image",
        title = "Initial image capture",
        caption = "Full-screen hardware image source"
    ),
    MovingCard(
        tabLabel = "Card",
        title = "Moving glass card",
        caption = "Scroll the cards over a static source"
    ),
    MovingSource(
        tabLabel = "Source",
        title = "Static glass card",
        caption = "Animated image under fixed blur"
    ),
    MovingCardAndSource(
        tabLabel = "Both",
        title = "Moving glass card",
        caption = "Scroll while the image moves underneath"
    )
}

@Composable
private fun VerificationApp() {
    var scenario by remember { mutableStateOf(VerificationScenario.InitialImage) }

    key(scenario) {
        TriLevelLayout(
            modifier = Modifier.fillMaxSize(),
            scaleFactor = 0.8f,
            debounceMs = 32L,
            background = {
                when (scenario) {
                    VerificationScenario.InitialImage -> HardwareImageBackdrop()
                    VerificationScenario.MovingCard -> StaticBackdrop()
                    VerificationScenario.MovingSource -> AnimatedBackdrop()
                    VerificationScenario.MovingCardAndSource -> AnimatedBackdrop()
                }
            },
            foreground = {},
            overlay = {
                VerificationOverlay(
                    scenario = scenario,
                    onScenarioChange = { scenario = it }
                )
            }
        )
    }
}

@Composable
private fun HardwareImageBackdrop(
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(R.drawable.img_test)
            .allowHardware(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun StaticBackdrop() {
    HardwareImageBackdrop()
}

@Composable
private fun AnimatedBackdrop() {
    val infinite = rememberInfiniteTransition(label = "animated-source")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "animated-source-t"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111318))
    ) {
        HardwareImageBackdrop(
            modifier = Modifier.graphicsLayer {
                scaleX = 1.18f
                scaleY = 1.18f
                translationX = 42f * sin(t)
                translationY = 64f * sin(t * 0.7f)
            }
        )
    }
}

@Composable
private fun VerificationOverlay(
    scenario: VerificationScenario,
    onScenarioChange: (VerificationScenario) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        when (scenario) {
            VerificationScenario.InitialImage -> InitialImageTestCard()
            VerificationScenario.MovingCard -> MovingCardTestList(
                scenario = VerificationScenario.MovingCard,
                caption = "Background crop should follow this card"
            )
            VerificationScenario.MovingSource -> StaticCardOnMovingSource()
            VerificationScenario.MovingCardAndSource -> MovingCardTestList(
                scenario = VerificationScenario.MovingCardAndSource,
                caption = "Moving source and moving region should both update"
            )
        }

        ScenarioTabs(
            selected = scenario,
            onSelected = onScenarioChange,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
        )
    }
}

@Composable
private fun ScenarioTabs(
    selected: VerificationScenario,
    onSelected: (VerificationScenario) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.34f))
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        VerificationScenario.entries.forEach { scenario ->
            val isSelected = scenario == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .clickable { onSelected(scenario) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = scenario.tabLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color.Black else Color.White,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BoxScope.InitialImageTestCard() {
    VerificationGlassCard(
        scenario = VerificationScenario.InitialImage,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 24.dp)
    )
}

@Composable
private fun BoxScope.MovingCardTestList(
    scenario: VerificationScenario,
    caption: String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 132.dp,
            bottom = 132.dp,
            start = 24.dp,
            end = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        items((1..12).toList()) { index ->
            Box(Modifier.fillMaxWidth()) {
                VerificationGlassCard(
                    scenario = scenario,
                    modifier = Modifier
                        .align(if (index % 2 == 0) Alignment.CenterEnd else Alignment.CenterStart)
                        .widthIn(max = 280.dp),
                    title = "Moving card $index",
                    caption = caption
                )
            }
        }
    }
}

@Composable
private fun BoxScope.StaticCardOnMovingSource() {
    VerificationGlassCard(
        scenario = VerificationScenario.MovingSource,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 24.dp)
    )
}

@Composable
private fun VerificationGlassCard(
    scenario: VerificationScenario,
    modifier: Modifier = Modifier,
    title: String = scenario.title,
    caption: String = scenario.caption,
    width: Dp = 320.dp
) {
    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = modifier
            .widthIn(max = width)
            .height(136.dp)
            .layeredBackdropCapture(
                layerName = "background",
                shape = shape,
                filter = BackdropFilter.Glass(blurRadiusIntensity = 8f)
            )
            .glassBorder(
                shape = shape,
                borderColor = Color.White,
                borderWidth = 1.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.14f))
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = caption,
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
