package com.builditcode.glass.core

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.builditcode.glass.core.backdrops.LayerBackdrop
import com.builditcode.glass.core.backdrops.emptyBackdrop
import com.builditcode.glass.core.backdrops.layerBackdrop
import com.builditcode.glass.core.backdrops.rememberLayerBackdrop
import com.builditcode.glass.core.highlight.Highlight
import com.builditcode.glass.core.shadow.InnerShadow
import com.builditcode.glass.core.shadow.Shadow

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

@Stable
class LayeredBackdropManager {
    private val backdrops = mutableStateMapOf<String, LayerBackdrop>()

    fun getBackdrop(layerName: String): LayerBackdrop? = backdrops[layerName]

    internal fun register(layerName: String, backdrop: LayerBackdrop) {
        backdrops[layerName] = backdrop
    }

    internal fun unregister(layerName: String, backdrop: LayerBackdrop) {
        if (backdrops[layerName] == backdrop) {
            backdrops.remove(layerName)
        }
    }
}

@Composable
fun rememberLayeredBackdropManager(): LayeredBackdropManager =
    remember { LayeredBackdropManager() }

@SuppressLint("ComposeCompositionLocalUsage")
val LocalLayeredBackdropManager = staticCompositionLocalOf<LayeredBackdropManager?> { null }

@SuppressLint("ComposeCompositionLocalUsage")
val LocalBackdropLayerName = staticCompositionLocalOf<String?> { null }

@Composable
fun rememberLayeredBackdrop(
    layerName: String? = LocalBackdropLayerName.current
): LayerBackdrop? {
    val manager = LocalLayeredBackdropManager.current ?: return null
    val name = layerName ?: return null
    return manager.getBackdrop(name)
}

@Composable
fun rememberLayeredBackdropOrEmpty(
    layerName: String? = LocalBackdropLayerName.current,
    fallback: Backdrop = emptyBackdrop()
): Backdrop = rememberLayeredBackdrop(layerName) ?: fallback

@Composable
fun Modifier.layeredBackdropSource(
    layerName: String,
    onDraw: ContentDrawScope.() -> Unit = { drawContent() }
): Modifier {
    val manager = LocalLayeredBackdropManager.current ?: return this
    val backdrop = rememberLayerBackdrop(onDraw = onDraw)

    DisposableEffect(manager, layerName, backdrop) {
        manager.register(layerName, backdrop)
        onDispose { manager.unregister(layerName, backdrop) }
    }

    return layerBackdrop(backdrop)
}

@Composable
fun Modifier.layeredBackdropCapture(
    layerName: String? = LocalBackdropLayerName.current,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val backdrop = rememberLayeredBackdrop(layerName) ?: return clip(shape())
    return drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = effects,
        highlight = highlight,
        shadow = shadow,
        innerShadow = innerShadow,
        layerBlock = layerBlock,
        onDrawBehind = onDrawBehind,
        onDrawBackdrop = onDrawBackdrop,
        onDrawSurface = onDrawSurface,
        onDrawFront = onDrawFront
    )
}
