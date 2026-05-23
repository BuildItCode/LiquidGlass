package com.builditcode.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(
    name = "All Liquid Controls",
    group = "Liquid Components",
    showBackground = true,
    backgroundColor = 0xFF101114
)
@Composable
fun LiquidControlsPreview() {
    LiquidPreviewScene {
        var search by remember { mutableStateOf("") }
        var toggle by remember { mutableStateOf(true) }
        var slider by remember { mutableStateOf(0.62f) }

        Column(
            modifier = Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            LiquidSearchBar(
                value = search,
                onValueChange = { search = it },
                placeholder = "Search apps",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LiquidButton(
                    text = "Continue",
                    onClick = {}
                )
                LiquidToggle(
                    checked = toggle,
                    onCheckedChange = { toggle = it }
                )
            }
            LiquidSlider(
                value = slider,
                onValueChange = { slider = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun LiquidPreviewScene(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.padding(16.dp)
    ) {
        content()
    }
}
