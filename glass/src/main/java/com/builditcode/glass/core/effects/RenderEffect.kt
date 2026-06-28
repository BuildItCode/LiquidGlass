package com.builditcode.glass.core.effects

import androidx.compose.ui.graphics.RenderEffect
import com.builditcode.glass.core.BackdropEffectScope
import com.builditcode.glass.core.RuntimeShader
import com.builditcode.glass.core.helpers.RuntimeShaderEffect
import com.builditcode.glass.core.helpers.chain
import com.builditcode.glass.core.isRenderEffectSupported
import com.builditcode.glass.core.isRuntimeShaderSupported
import org.intellij.lang.annotations.Language
import kotlin.contracts.ExperimentalContracts

fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (!isRenderEffectSupported()) return

    renderEffect = renderEffect.chain(effect)
}

@OptIn(ExperimentalContracts::class)
fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    @Language("AGSL") shaderString: String,
    uniformShaderName: String,
    block: RuntimeShader.() -> Unit
) {
    if (!isRuntimeShaderSupported()) return

    val effect =
        RuntimeShaderEffect(
            runtimeShader = obtainRuntimeShader(key, shaderString).apply(block),
            uniformShaderName = uniformShaderName
        )
    renderEffect = renderEffect.chain(effect)
}
