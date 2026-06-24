package com.builditcode.glass

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Layer names used internally by [TriLevelLayout].
 *
 * Use these constants when adding [layeredBackdropCapture] to content inside a
 * [TriLevelLayout]. Foreground content should usually sample [Background]. Overlay
 * content can sample [Foreground] or [Background]. Do not sample the layer that currently
 * contains the glass component.
 */
object TrilevelLayers {
    const val Background = "background"
    const val Foreground = "foreground"
    const val Overlay = "overlay"
}

/**
 * Layer names used internally by [QuadLevelLayout].
 *
 * Use these constants when adding [layeredBackdropCapture] to content inside a
 * [QuadLevelLayout]. Captures should point to a lower/previous layer, never the layer
 * currently containing the glass component.
 */
object QuadLevelLayers {
    const val Background = "background"
    const val Midground = "midground"
    const val Foreground = "foreground"
    const val Overlay = "overlay"
}

/**
 * A three-level composable layout that wires the backdrop blur pipeline automatically.
 *
 * Levels are stacked in Z-order: background → foreground → overlay.
 * Each level acts as a backdrop source for the levels above it, enabling
 * [layeredBackdropCapture] modifiers in foreground/overlay content to sample
 * blurred pixels from the layers beneath.
 *
 * Do not capture the same layer a component lives in. Foreground content should sample
 * [TrilevelLayers.Background]. Overlay content can sample [TrilevelLayers.Foreground]
 * or [TrilevelLayers.Background].
 *
 * @param scaleFactor Internal resolution scale for backdrop rasterization (e.g. 0.5 = 50%).
 * @param debounceMs Minimum interval between full re-captures in milliseconds.
 * @param enableHardwareCapture When FALSE, source capture uses the software
 * picture/bitmap path and hardware snapshot promotion is disabled. Ignored when
 * [manager] is supplied.
 * @param background Bottommost layer, captured under [TrilevelLayers.Background].
 * @param foreground Middle layer rendered above background, captured under [TrilevelLayers.Foreground].
 * @param overlay Topmost layer — not a capture source; use [layeredBackdropCapture] here
 *                with [TrilevelLayers.Background] or [TrilevelLayers.Foreground] to sample beneath it.
 */
@Composable
fun TriLevelLayout(
    background: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    foreground: (@Composable () -> Unit)? = null,
    overlay: (@Composable () -> Unit)? = null,
    scaleFactor: Float = 0.6f,
    debounceMs: Long = 32L,
    manager: BackdropLayerManager? = null,
    enableHardwareCapture: Boolean = true,
    shouldCapture: Boolean = true
) {
    val manager = if(!shouldCapture) null else manager ?: rememberBackdropManager(scaleFactor, defaultDebounceMs = debounceMs, disableHardwareAcceleration = !enableHardwareCapture)

    // Only stand a level up as a capture source if some level above it can actually sample it.
    val captureForeground = overlay != null
    val captureBackground = foreground != null || captureForeground

    CompositionLocalProvider(
        LocalBackdropLayerManager provides manager
    ) {
        LayeredLayout(modifier = modifier) {
            layer(tag = TrilevelLayers.Background) { _ ->
                CompositionLocalProvider(LocalBackdropLayerName provides null) {
                    Box(
                        if (captureBackground) Modifier.layeredBackdropSource(TrilevelLayers.Background)
                        else Modifier
                    ) {
                        background()
                    }
                }
            }
            layer(tag = TrilevelLayers.Foreground) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides TrilevelLayers.Background) {
                    Box(
                        if (captureForeground) Modifier.layeredBackdropSource(TrilevelLayers.Foreground)
                        else Modifier
                    ) {
                        previousLayers()
                        foreground?.invoke()
                    }
                }
            }
            layer(tag = TrilevelLayers.Overlay) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides TrilevelLayers.Foreground) {
                    Box {
                        previousLayers()
                        overlay?.invoke()
                    }
                }
            }
        }
    }
}

/**
 * A four-level composable layout that wires the backdrop blur pipeline automatically.
 *
 * Levels are stacked in Z-order: background → midground → foreground → overlay.
 * The first three levels are registered as capture sources, so higher levels can
 * sample any lower source layer through [layeredBackdropCapture].
 *
 * Do not capture the same layer a component lives in. Each glass component should sample
 * a lower/previous layer such as background, midground, or foreground depending on where
 * the component is placed.
 *
 * @param scaleFactor Internal resolution scale for backdrop rasterization (e.g. 0.5 = 50%).
 * @param debounceMs Minimum interval between full re-captures in milliseconds.
 * @param enableHardwareCapture When FALSE, source capture uses the software
 * picture/bitmap path and hardware snapshot promotion is disabled. Ignored when
 * [manager] is supplied.
 * @param background Bottommost layer, captured under [QuadLevelLayers.Background].
 * @param midground Layer rendered above background, captured under [QuadLevelLayers.Midground].
 * @param foreground Layer rendered above midground, captured under [QuadLevelLayers.Foreground].
 * @param overlay Topmost layer — not a capture source; use [layeredBackdropCapture] here
 *                with any source in [QuadLevelLayers] except [QuadLevelLayers.Overlay].
 */
@Composable
fun QuadLevelLayout(
    background: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    midground: (@Composable () -> Unit)? = null,
    foreground: (@Composable () -> Unit)? = null,
    overlay: (@Composable () -> Unit)? = null,
    scaleFactor: Float = 0.6f,
    debounceMs: Long = 32L,
    manager: BackdropLayerManager? = null,
    shouldCapture: Boolean = true,
    enableHardwareCapture: Boolean = false
) {
    val manager = if(!shouldCapture) null else manager ?: rememberBackdropManager(scaleFactor, defaultDebounceMs = debounceMs, disableHardwareAcceleration = !enableHardwareCapture)

    // Only stand a level up as a capture source if some level above it can actually sample it.
    // When every level above is empty there is no consumer, so skip layeredBackdropSource and
    // never capture that backdrop.
    val captureForeground = overlay != null
    val captureMidground = foreground != null || captureForeground
    val captureBackground = midground != null || captureMidground

    CompositionLocalProvider(
        LocalBackdropLayerManager provides manager
    ) {
        LayeredLayout(modifier = modifier) {
            layer(tag = QuadLevelLayers.Background) { _ ->
                CompositionLocalProvider(LocalBackdropLayerName provides null) {
                    Box(
                        if (captureBackground) Modifier.layeredBackdropSource(QuadLevelLayers.Background)
                        else Modifier
                    ) {
                        background()
                    }
                }
            }
            layer(tag = QuadLevelLayers.Midground) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides QuadLevelLayers.Background) {
                    Box(
                        if (captureMidground) Modifier.layeredBackdropSource(QuadLevelLayers.Midground)
                        else Modifier
                    ) {
                        previousLayers()
                        midground?.invoke()
                    }
                }
            }
            layer(tag = QuadLevelLayers.Foreground) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides QuadLevelLayers.Midground) {
                    Box(
                        if (captureForeground) Modifier.layeredBackdropSource(QuadLevelLayers.Foreground)
                        else Modifier
                    ) {
                        previousLayers()
                        foreground?.invoke()
                    }
                }
            }
            layer(tag = QuadLevelLayers.Overlay) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides QuadLevelLayers.Foreground) {
                    Box {
                        previousLayers()
                        overlay?.invoke()
                    }
                }
            }
        }
    }
}
@SuppressLint("ComposeCompositionLocalUsage")
val LocalBackdropLayerName = staticCompositionLocalOf<String?> { null }