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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.builditcode.glass.BackdropFilter
import com.builditcode.glass.TriLevelLayout
import com.builditcode.glass.glassBorder
import com.builditcode.glass.layeredBackdropCapture
import com.builditcode.liquidglass.ui.theme.LiquidGlassTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiquidGlassTheme {
                TriLevelLayout(
                    modifier = Modifier.fillMaxSize(),
                    scaleFactor = 0.8f,
                    debounceMs = 32L,
                    background = { Backdrop() },
                    foreground = { GlassForeground() },
                    overlay = { GlassOverlay() }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Its per-frame redraw is what drives the glass layer's live recapture.
// ---------------------------------------------------------------------------

@Composable
private fun Backdrop() {
    Image(
        painter = painterResource(id = R.drawable.img_test),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = 1f,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    )
}

// ---------------------------------------------------------------------------
// Foreground — glass pieces sampling the animated "background" layer.
// ---------------------------------------------------------------------------

@Composable
private fun GlassForeground() {
    Box(modifier = Modifier.fillMaxSize()) {
        FloatingOrb()
        FilterRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Overlay — glass pieces sampling the "foreground" layer.
// ---------------------------------------------------------------------------

@Composable
private fun GlassOverlay() {
    Box(modifier = Modifier.fillMaxSize()) {
        Panel()
    }
}

@Composable
private fun BoxScope.FloatingOrb() {
    val infinite = rememberInfiniteTransition(label = "orb")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "orbT"
    )
    // Lissajous figure-8: x sweeps once per loop, y twice.
    val x = 140.dp * sin(t)
    val y = (-120).dp + 60.dp * sin(2f * t)

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = x, y = y)
            .size(128.dp)
            .glassBorder(
                shape = CircleShape,
                borderColor = Color.White,
                borderWidth = 1.5.dp,
            )
            .layeredBackdropCapture(
                layerName = "background",
                shape = CircleShape,
                filter = BackdropFilter.Glass(
                    cornerRadiusDp = 64f,
                    refraction = 0.30f,
                    curve = 0.45f,
                    dispersion = 0.22f,
                    edge = 0.35f,
                    blurRadiusIntensity = 1.5f,
                )
            )
    )
}

@Composable
private fun BoxScope.Panel() {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(width = 220.dp, height = 96.dp)
            .layeredBackdropCapture(
                layerName = "foreground",
                shape = RoundedCornerShape(48.dp),
                filter = BackdropFilter.Glass(
                    cornerRadiusDp = 48f,
                    refraction = 0.22f,
                    curve = 0.30f,
                    dispersion = 0.18f,
                    edge = 0.25f,
                    blurRadiusIntensity = 5f,
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("Liquid glass", style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
private fun FilterRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FilterSample(
            label = "Lens",
            filter = BackdropFilter.Glass(
                cornerRadiusDp = 22f,
                refraction = 0.55f,
                curve = 0.55f,
                dispersion = 0.38f,
                edge = 0.38f,
                blurRadiusIntensity = 1f,
            )
        )
        FilterSample(
            label = "Frost",
            filter = BackdropFilter.Glass(
                cornerRadiusDp = 22f,
                blurRadiusIntensity = 5f,
                edge = 0.15f,
                refraction = 0.08f,
            )
        )
        FilterSample(
            label = "Blur",
            filter = BackdropFilter.Blur(blurRadiusIntensity = 10f)
        )
    }
}

@Composable
private fun FilterSample(label: String, filter: BackdropFilter) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .glassBorder(
                    shape = RoundedCornerShape(22.dp),
                    borderColor = Color.White,
                    borderWidth = 1.dp,
                )
                .layeredBackdropCapture(
                    layerName = "background",
                    shape = RoundedCornerShape(22.dp),
                    filter = filter
                )
        )
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
