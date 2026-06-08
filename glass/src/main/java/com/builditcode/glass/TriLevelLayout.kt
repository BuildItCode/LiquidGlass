package com.builditcode.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 * @param background Bottommost layer, captured under [TrilevelLayers.Background].
 * @param foreground Middle layer rendered above background, captured under [TrilevelLayers.Foreground].
 * @param overlay Topmost layer — not a capture source; use [layeredBackdropCapture] here
 *                with [TrilevelLayers.Background] or [TrilevelLayers.Foreground] to sample beneath it.
 */
@Composable
fun TriLevelLayout(
    modifier: Modifier = Modifier,
    scaleFactor: Float = 0.6f,
    manager: BackdropLayerManager? = null,
    background: @Composable () -> Unit,
    foreground: @Composable () -> Unit,
    overlay: @Composable () -> Unit
) {
    val manager = manager ?: rememberBackdropManager(scaleFactor)

    CompositionLocalProvider(
        LocalBackdropLayerManager provides manager
    ) {
        LayeredLayout(modifier = modifier) {
            layer(tag = TrilevelLayers.Background) { _ ->
                Box(Modifier.layeredBackdropSource(TrilevelLayers.Background)) {
                    background()
                }
            }
            layer(tag = TrilevelLayers.Foreground) { previousLayers ->
                Box(Modifier.layeredBackdropSource(TrilevelLayers.Foreground)) {
                    previousLayers()
                    foreground()
                }
            }
            layer(tag = TrilevelLayers.Overlay) { previousLayers ->
                Box {
                    previousLayers()
                    overlay()
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
 * @param background Bottommost layer, captured under [QuadLevelLayers.Background].
 * @param midground Layer rendered above background, captured under [QuadLevelLayers.Midground].
 * @param foreground Layer rendered above midground, captured under [QuadLevelLayers.Foreground].
 * @param overlay Topmost layer — not a capture source; use [layeredBackdropCapture] here
 *                with any source in [QuadLevelLayers] except [QuadLevelLayers.Overlay].
 */
@Composable
fun QuadLevelLayout(
    modifier: Modifier = Modifier,
    scaleFactor: Float = 0.6f,
    manager: BackdropLayerManager? = null,
    background: @Composable () -> Unit,
    midground: @Composable () -> Unit,
    foreground: @Composable () -> Unit,
    overlay: @Composable () -> Unit
) {
    val manager = manager ?: rememberBackdropManager(scaleFactor)

    CompositionLocalProvider(
        LocalBackdropLayerManager provides manager
    ) {
        LayeredLayout(modifier = modifier) {
            layer(tag = QuadLevelLayers.Background) { _ ->
                Box(Modifier.layeredBackdropSource(QuadLevelLayers.Background)) {
                    background()
                }
            }
            layer(tag = QuadLevelLayers.Midground) { previousLayers ->
                Box(Modifier.layeredBackdropSource(QuadLevelLayers.Midground)) {
                    previousLayers()
                    midground()
                }
            }
            layer(tag = QuadLevelLayers.Foreground) { previousLayers ->
                Box(Modifier.layeredBackdropSource(QuadLevelLayers.Foreground)) {
                    previousLayers()
                    foreground()
                }
            }
            layer(tag = QuadLevelLayers.Overlay) { previousLayers ->
                Box {
                    previousLayers()
                    overlay()
                }
            }
        }
    }
}
