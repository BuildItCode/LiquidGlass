package com.builditcode.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * A three-level composable layout that wires the backdrop blur pipeline automatically.
 *
 * Levels are stacked in Z-order: background → foreground → overlay.
 * Each level acts as a backdrop source for the levels above it, enabling
 * [layeredBackdropCapture] modifiers in foreground/overlay content to sample
 * blurred pixels from the layers beneath.
 *
 * @param scaleFactor Internal resolution scale for backdrop rasterization (e.g. 0.5 = 50%).
 * @param debounceMs Minimum interval between full re-captures in milliseconds.
 * @param background Bottommost layer, captured under the "background" tag.
 * @param foreground Middle layer rendered above background, captured under the "foreground" tag.
 * @param overlay Topmost layer — not a capture source; use [layeredBackdropCapture] here
 *                with layerName "background" or "foreground" to sample beneath it.
 */
@Composable
fun TriLevelLayout(
    modifier: Modifier = Modifier,
    scaleFactor: Float = 0.6f,
    debounceMs: Long = 32L,
    background: @Composable () -> Unit,
    foreground: @Composable () -> Unit,
    overlay: @Composable () -> Unit
) {
    val manager = rememberBackdropManager(scaleFactor, defaultDebounceMs = debounceMs)

    CompositionLocalProvider(
        LocalBackdropLayerManager provides manager
    ) {
        LayeredLayout(modifier = modifier) {
            layer(tag = "background") { _ ->
                Box(Modifier.layeredBackdropSource("background")) {
                    background()
                }
            }
            layer(tag = "foreground") { previousLayers ->
                Box(Modifier.layeredBackdropSource("foreground")) {
                    previousLayers()
                    foreground()
                }
            }
            layer(tag = "overlay") { previousLayers ->
                Box {
                    previousLayers()
                    overlay()
                }
            }
        }
    }
}