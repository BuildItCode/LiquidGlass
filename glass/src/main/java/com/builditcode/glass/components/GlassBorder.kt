package com.builditcode.glass.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.SweepGradient
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.atan2

/**
 * Draws the decorative rim/edge treatment used by glass surfaces.
 *
 * This modifier only draws the border highlight. It does not capture, blur, or distort
 * content behind the composable. Pair it with [layeredBackdropCapture] when the surface
 * also needs a live backdrop effect.
 *
 * @param shape Shape whose outline receives the border.
 * @param borderColor Base color for the rim highlight.
 * @param borderWidth Stroke width for the rim.
 * @param gapSize Fraction of the sweep kept transparent around the light gaps.
 * @param softness Fraction used to feather the gap edges.
 * @param overlayBrush Optional fill drawn inside the same outline after the rim.
 * @param rotationDegrees Additional rotation applied to the sweep highlight. Keep this at
 * zero for a static border, or pass [rememberGlassBorderGyroscopeRotation] for an opt-in
 * device-motion highlight.
 */
fun Modifier.glassBorder(
    shape: Shape,
    borderColor: Color,
    borderWidth: Dp,
    gapSize: Float = 0.1f, // Default sensible value
    softness: Float = 0.05f,
    overlayBrush: Brush? = null,
    rotationDegrees: Float = 0f
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
                    postRotate(angleToCorner + rotationDegrees, center.x, center.y)
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

private const val GlassGyroscopeMaxRotationDegrees = 42f
private const val GlassGyroscopeSensitivity = 1.15f
private const val GlassGyroscopeSmoothing = 0.22f

/**
 * Returns a small border-rotation offset driven by device motion.
 *
 * The helper is intentionally opt-in because it registers a sensor listener. It prefers the
 * game rotation vector sensor, which is backed by device-motion fusion and gives stable tilt
 * data without requiring magnetic north. Devices without a rotation-vector sensor fall back
 * to raw gyroscope integration with light damping.
 *
 * Use the returned value as [glassBorder]'s `rotationDegrees`.
 */
@Composable
fun rememberGlassBorderGyroscopeRotation(enabled: Boolean = true): Float {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var rotationDegrees by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) {
            rotationDegrees = 0f
            return@DisposableEffect onDispose { }
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensorManager == null || sensor == null) {
            rotationDegrees = 0f
            return@DisposableEffect onDispose { }
        }

        var registered = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values.getOrNull(0) ?: return
                val y = event.values.getOrNull(1) ?: return
                val targetDegrees = Math.toDegrees(atan2(-x.toDouble(), y.toDouble()))
                    .toFloat()
                    .let { it * GlassGyroscopeSensitivity }
                    .coerceIn(-GlassGyroscopeMaxRotationDegrees, GlassGyroscopeMaxRotationDegrees)

                rotationDegrees += (targetDegrees - rotationDegrees) * GlassGyroscopeSmoothing
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun register() {
            if (!registered) {
                registered = sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME,
                )
            }
        }

        fun unregister() {
            if (registered) {
                sensorManager.unregisterListener(listener)
                registered = false
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> unregister()

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        onDispose {
            unregister()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            rotationDegrees = 0f
        }
    }

    return rotationDegrees
}

private fun tiltToBorderRotation(
    pitch: Float,
    roll: Float,
    displayRotation: Int
): Float =
    when (displayRotation) {
        Surface.ROTATION_90 -> roll * 0.34f - pitch * 0.48f
        Surface.ROTATION_180 -> -roll * 0.48f - pitch * 0.34f
        Surface.ROTATION_270 -> -roll * 0.34f + pitch * 0.48f
        else -> roll * 0.48f + pitch * 0.34f
    }

@Suppress("DEPRECATION")
private fun Context.displayRotation(): Int =
    (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        ?.defaultDisplay
        ?.rotation
        ?: Surface.ROTATION_0

val noFlingBehavior = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        // Return 0 to indicate all velocity has been "consumed"
        // and no kinetic scrolling should occur.
        return 0f
    }
}