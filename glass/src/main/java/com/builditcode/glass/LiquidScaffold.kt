package com.builditcode.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Current [LiquidScaffoldState], when content is composed inside [LiquidScaffold].
 */
val LocalLiquidScaffoldState = staticCompositionLocalOf<LiquidScaffoldState?> { null }

/**
 * Handle returned by [LiquidScaffoldState.addComponent].
 *
 * Keep this handle when adding temporary surfaces, popups, sheets, or overlays so the
 * registered composable can be removed when it is no longer needed.
 */
fun interface LiquidScaffoldComponentHandle {
    fun remove()
}

/**
 * State for [LiquidScaffold].
 *
 * The state owns a [BackdropLayerManager] and a small registry of layer-bound composables.
 * Dynamic components can be registered from event handlers, controllers, or child content
 * that has access to this state.
 */
@Stable
class LiquidScaffoldState internal constructor(
    val backdropManager: BackdropLayerManager
) {
    private val components = mutableStateListOf<LiquidScaffoldComponent>()
    private var nextComponentId by mutableIntStateOf(0)

    /**
     * Add [content] to [layer] and return a handle that removes it.
     *
     * Use [QuadLevelLayers.Background], [QuadLevelLayers.Midground],
     * [QuadLevelLayers.Foreground], or [QuadLevelLayers.Overlay] for [layer].
     */
    fun addComponent(
        layer: String = QuadLevelLayers.Overlay,
        content: @Composable () -> Unit
    ): LiquidScaffoldComponentHandle {
        val key = nextComponentId++
        return addComponent(key = key, layer = layer, content = content)
    }

    /**
     * Add or replace a component identified by [key] in [layer].
     *
     * Supplying a stable key is useful when an owner may call this more than once and
     * wants replacement instead of duplicate registrations.
     */
    fun addComponent(
        key: Any,
        layer: String = QuadLevelLayers.Overlay,
        content: @Composable () -> Unit
    ): LiquidScaffoldComponentHandle {
        removeComponent(key)
        components += LiquidScaffoldComponent(key = key, layer = layer, content = content)
        return LiquidScaffoldComponentHandle { removeComponent(key) }
    }

    fun removeComponent(key: Any) {
        components.removeAll { it.key == key }
    }

    fun clearLayer(layer: String) {
        components.removeAll { it.layer == layer }
    }

    fun clearComponents() {
        components.clear()
    }

    @Composable
    internal fun RenderLayer(layer: String) {
        components.forEach { component ->
            if (component.layer == layer) {
                key(component.key) {
                    component.content()
                }
            }
        }
    }
}

private class LiquidScaffoldComponent(
    val key: Any,
    val layer: String,
    val content: @Composable () -> Unit
)

@Stable
class LiquidScaffoldScope internal constructor(
    val state: LiquidScaffoldState
)

/**
 * Remember a [LiquidScaffoldState] with its own [BackdropLayerManager].
 */
@Composable
fun rememberLiquidScaffoldState(
    scaleFactor: Float = 0.6f,
    debounceMs: Long = 32L,
    disableHardwareAcceleration: Boolean = false
): LiquidScaffoldState {
    val manager = rememberBackdropManager(
        defaultScaleFactor = scaleFactor,
        defaultDebounceMs = debounceMs,
        disableHardwareAcceleration = disableHardwareAcceleration
    )
    return remember(manager) { LiquidScaffoldState(manager) }
}

/**
 * A Quad-level glass scaffold with a stateful dynamic component registry.
 *
 * Static slot content and dynamically registered components are rendered into the same
 * four layers. The first three layers are capture sources; overlay is the top layer and
 * is not itself a source. Captures should still sample a lower/previous layer, never the
 * same layer that contains the glass component.
 */
@Composable
fun LiquidScaffold(
    modifier: Modifier = Modifier,
    state: LiquidScaffoldState = rememberLiquidScaffoldState(),
    background: @Composable LiquidScaffoldScope.() -> Unit = {},
    midground: @Composable LiquidScaffoldScope.() -> Unit = {},
    foreground: @Composable LiquidScaffoldScope.() -> Unit = {},
    overlay: @Composable LiquidScaffoldScope.() -> Unit = {}
) {
    val scope = remember(state) { LiquidScaffoldScope(state) }

    CompositionLocalProvider(LocalLiquidScaffoldState provides state) {
        QuadLevelLayout(
            modifier = modifier,
            manager = state.backdropManager,
            background = {
                scope.background()
                state.RenderLayer(QuadLevelLayers.Background)
            },
            midground = {
                scope.midground()
                state.RenderLayer(QuadLevelLayers.Midground)
            },
            foreground = {
                scope.foreground()
                state.RenderLayer(QuadLevelLayers.Foreground)
            },
            overlay = {
                scope.overlay()
                state.RenderLayer(QuadLevelLayers.Overlay)
            }
        )
    }
}
