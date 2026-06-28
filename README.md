# Liquid Glass

A Jetpack Compose library for live layered glass surfaces. Glass panels sample the UI layer behind
them, then apply blur, vibrancy, color controls, and AGSL refraction effects through Compose
graphics layers.

Part of the **Lucid** design system.

---

## Features

- **Live layered sampling**: foreground and overlay glass can sample named source layers beneath
  them.
- **Tri and quad layouts**: `TriLevelLayout` and `QuadLevelLayout` wire the layer names and
  composition locals for common background -> foreground -> overlay stacks.
- **RenderEffect blur**: blur, vibrancy, and color controls are available on API 31+.
- **AGSL lens refraction**: rounded-rect lens distortion, depth, and chromatic aberration are
  available on API 33+.
- **Adaptive luminance glass**: a reusable capture modifier can sample the underlying layer and
  adjust the glass effect for dark, neutral, or bright content.
- **Liquid controls**: `LiquidButton`, `LiquidCard`, `LiquidSearchBar`, `LiquidToggle`,
  `LiquidSlider`, and `LiquidBottomTabs` use the same layered backdrop pipeline.
- **Sweep-gradient glass border**: `Modifier.glassBorder` adds the rim highlight used by the
  components and demos.

---

## Install

Published on [JitPack](https://jitpack.io).

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.BuildItCode:LiquidGlass:0.6.2")
}
```

Or with a version catalog:

```toml
[versions]
glass = "0.6.2"

[libraries]
glass = { group = "com.github.BuildItCode", name = "LiquidGlass", version.ref = "glass" }
```

---

## Quick Start

`TriLevelLayout` provides a background source layer and lets overlay content sample it automatically
through `LocalBackdropLayerName`.

```kotlin
setContent {
    TriLevelLayout(
        modifier = Modifier.fillMaxSize(),
        background = {
            Image(
                painter = painterResource(R.drawable.wallpaper),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        },
        overlay = {
            val shape = RoundedRectangle(32.dp)

            Box(
                modifier = Modifier
                    .size(220.dp)
                    .layeredBackdropCapture(
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(
                                refractionHeight = 12.dp.toPx(),
                                refractionAmount = 24.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.14f))
                        }
                    )
                    .glassBorder(shape, Color.White, 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Glass", color = Color.White)
            }
        }
    )
}
```

Use `QuadLevelLayout` when you need one extra source layer: background -> midground -> foreground ->
overlay. The exposed layer names are `TrilevelLayers` and `QuadLevelLayers` in
`com.builditcode.glass.layout`.

---

## Adaptive Luminance

Use `layeredAdaptiveLuminanceBackdropCapture` when the glass should react to the sampled backdrop
brightness.

```kotlin
val luminanceState = rememberAdaptiveLuminanceState()

Box(
    modifier = Modifier.layeredAdaptiveLuminanceBackdropCapture(
        state = luminanceState,
        shape = { RoundedRectangle(28.dp) },
        effects = {
            vibrancy()
            adaptiveLuminanceGlass()
            lens(10.dp.toPx(), 20.dp.toPx())
        }
    )
) {
    Text(
        text = "%.2f".format(luminanceState.luminance),
        color = luminanceState.contentColor.value
    )
}
```

The state starts at neutral luminance (`0.5f`) to avoid first-frame flashes while the backdrop layer
is warming up, then updates from the first valid capture sample.

---

## Components

The component APIs are designed to be dropped into a `TriLevelLayout` or `QuadLevelLayout` overlay:

```kotlin
LiquidButton(text = "Continue", onClick = onContinue)
LiquidCard { /* content */ }
LiquidSearchBar(value = query, onValueChange = { query = it })
```

Adaptive luminance is on by default for the component glass. Disable it when you want a neutral
glass profile that keeps the lens and blur but skips luminance sampling:

```kotlin
LiquidCard(adaptiveLuminance = false) { /* content */ }
```

For controls ported from the NewGlass implementation, pass the current backdrop explicitly:

```kotlin
val backdrop = rememberLayeredBackdropOrEmpty()

LiquidToggle(
    selected = { enabled },
    onSelect = { enabled = it },
    backdrop = backdrop
)

LiquidSlider(
    value = { intensity },
    onValueChange = { intensity = it },
    valueRange = 0f..1f,
    visibilityThreshold = 0.001f,
    backdrop = backdrop
)
```

`LiquidBottomTabs`, `LiquidSlider`, and `LiquidToggle` expose color parameters with the same
light/dark defaults used by the built-in components.

---

## Requirements

- **Min SDK:** 31
- **Target SDK:** 36
- **Compose BOM:** 2026.02.01+ (tested)
- **Kotlin:** 2.2+
- **API 31-32:** live layer capture, blur, vibrancy, color controls, borders, and component
  surfaces.
- **API 33+:** full AGSL lens refraction, depth effect, SDF shaders, and chromatic aberration.

---

## Sample App

The `:app` module contains two screens:

- `GlassPlaygroundContent`: live adaptive luminance and lens controls.
- `ComponentShowcaseContent`: buttons, cards, search, toggle, slider, and bottom tabs over a layered
  backdrop.

---

## License

MIT
