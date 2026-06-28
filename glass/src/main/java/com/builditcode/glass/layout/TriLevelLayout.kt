package com.builditcode.glass.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.builditcode.glass.core.LayeredBackdropManager
import com.builditcode.glass.core.LocalBackdropLayerName
import com.builditcode.glass.core.LocalLayeredBackdropManager
import com.builditcode.glass.core.layeredBackdropSource
import com.builditcode.glass.core.rememberLayeredBackdropManager

object TrilevelLayers {
    const val Background = "background"
    const val Foreground = "foreground"
    const val Overlay = "overlay"
}

object QuadLevelLayers {
    const val Background = "background"
    const val Midground = "midground"
    const val Foreground = "foreground"
    const val Overlay = "overlay"
}

@Composable
fun TriLevelLayout(
    background: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    foreground: (@Composable () -> Unit)? = null,
    overlay: (@Composable () -> Unit)? = null,
    manager: LayeredBackdropManager = rememberLayeredBackdropManager(),
    shouldCapture: Boolean = true
) {
    val activeManager = if (shouldCapture) manager else null
    val captureForeground = overlay != null
    val captureBackground = foreground != null || captureForeground

    CompositionLocalProvider(LocalLayeredBackdropManager provides activeManager) {
        LayeredLayout(modifier = modifier) {
            layer(tag = TrilevelLayers.Background) { _ ->
                CompositionLocalProvider(LocalBackdropLayerName provides null) {
                    Box(
                        if (captureBackground) {
                            Modifier.layeredBackdropSource(TrilevelLayers.Background)
                        } else {
                            Modifier
                        }
                    ) {
                        background()
                    }
                }
            }
            layer(tag = TrilevelLayers.Foreground) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides TrilevelLayers.Background) {
                    Box(
                        if (captureForeground) {
                            Modifier.layeredBackdropSource(TrilevelLayers.Foreground)
                        } else {
                            Modifier
                        }
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

@Composable
fun QuadLevelLayout(
    background: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    midground: (@Composable () -> Unit)? = null,
    foreground: (@Composable () -> Unit)? = null,
    overlay: (@Composable () -> Unit)? = null,
    manager: LayeredBackdropManager = rememberLayeredBackdropManager(),
    shouldCapture: Boolean = true
) {
    val activeManager = if (shouldCapture) manager else null
    val captureForeground = overlay != null
    val captureMidground = foreground != null || captureForeground
    val captureBackground = midground != null || captureMidground

    CompositionLocalProvider(LocalLayeredBackdropManager provides activeManager) {
        LayeredLayout(modifier = modifier) {
            layer(tag = QuadLevelLayers.Background) { _ ->
                CompositionLocalProvider(LocalBackdropLayerName provides null) {
                    Box(
                        if (captureBackground) {
                            Modifier.layeredBackdropSource(QuadLevelLayers.Background)
                        } else {
                            Modifier
                        }
                    ) {
                        background()
                    }
                }
            }
            layer(tag = QuadLevelLayers.Midground) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides QuadLevelLayers.Background) {
                    Box(
                        if (captureMidground) {
                            Modifier.layeredBackdropSource(QuadLevelLayers.Midground)
                        } else {
                            Modifier
                        }
                    ) {
                        previousLayers()
                        midground?.invoke()
                    }
                }
            }
            layer(tag = QuadLevelLayers.Foreground) { previousLayers ->
                CompositionLocalProvider(LocalBackdropLayerName provides QuadLevelLayers.Midground) {
                    Box(
                        if (captureForeground) {
                            Modifier.layeredBackdropSource(QuadLevelLayers.Foreground)
                        } else {
                            Modifier
                        }
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
