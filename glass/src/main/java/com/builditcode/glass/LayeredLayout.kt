package com.builditcode.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Scope allowing you to define layers with tags.
 */
interface LayerScope {
    fun layer(
        tag: String,
        content: @Composable (previousLayers: @Composable () -> Unit) -> Unit
    )
}

internal class LayerScopeImpl : LayerScope {
    class LayerDefinition(
        val tag: String,
        val content: @Composable (previousLayers: @Composable () -> Unit) -> Unit
    )

    val layers = mutableListOf<LayerDefinition>()

    override fun layer(
        tag: String,
        content: @Composable (previousLayers: @Composable () -> Unit) -> Unit
    ) {
        layers.add(LayerDefinition(tag, content))
    }
}

/**
 * A reusable container that progressively wraps layers.
 */
@Composable
fun LayeredLayout(
    modifier: Modifier = Modifier,
    layers: LayerScope.() -> Unit
) {
    val scope = LayerScopeImpl().apply(layers)

    var composedTree: @Composable () -> Unit = {}

    for (layerDef in scope.layers) {
        val previousTree = composedTree
        composedTree = {
            layerDef.content(previousTree)
        }
    }

    Box(modifier) { composedTree() }
}