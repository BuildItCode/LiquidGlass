package com.builditcode.glass

import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlin.math.atan2

fun Modifier.glassBorder(
    shape: Shape,
    borderColor: Color,
    borderWidth: Dp,
    gapSize: Float = 0.1f, // Default sensible value
    softness: Float = 0.05f,
    overlayBrush: Brush? = null
) = this.drawWithCache {
    // 1. Calculate Geometry (Angle to Top-Right Corner)
    val dx = size.width / 2
    val dy = -(size.height / 2) // Invert Y for Cartesian coords
    val angleToCorner = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

    // 2. define "Safe" limits to prevent crashes
    val halfGap = (gapSize / 2f).coerceIn(0f, 0.4f)
    val soft = softness.coerceIn(0f, 0.1f)

    // 3. Define the Alpha Stops (Transparent -> Color -> Transparent)
    // This creates gaps at 0.0 (Start/Top-Right) and 0.5 (Opposite/Bottom-Left)
    val stops = floatArrayOf(
        0.0f,
        halfGap,
        halfGap + soft,                    // Gap 1 (Start)
        0.5f - halfGap - soft,
        0.5f - halfGap,
        0.5f + halfGap,
        0.5f + halfGap + soft, // Gap 2 (Middle)
        1.0f - halfGap - soft,
        1.0f - halfGap,
        1.0f       // Gap 1 (Wrap-around)
    )

    val c = borderColor.copy(alpha = 0.9f).toArgb()
    val t = Color.Transparent.toArgb()

    val colors = intArrayOf(
        t, t, c,       // Start Gap
        c, t, t, c,    // Middle Gap
        c, t, t        // End Gap
    )

    // 4. Create the Shader
    val borderBrush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val center = size.center
            return SweepGradient(center.x, center.y, colors, stops).apply {
                // Rotate the gradient so the "Gap" aligns with the corner angle calculated above
                setLocalMatrix(Matrix().apply {
                    postRotate(angleToCorner, center.x, center.y)
                })
            }
        }
    }

    // Cache outline and stroke width — only recomputed when size/density changes
    val outline = shape.createOutline(size, layoutDirection, this)
    val strokeWidth = borderWidth.toPx()

    onDrawWithContent {
        drawContent() // Draw the child content first

        // Draw the Overlay (e.g., a white gloss) if provided
        overlayBrush?.let { drawRect(brush = it) }

        // Draw the Border on top
        drawOutline(outline = outline, brush = borderBrush, style = Stroke(width = strokeWidth))
    }
}

val noFlingBehavior = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        // Return 0 to indicate all velocity has been "consumed"
        // and no kinetic scrolling should occur.
        return 0f
    }
}
