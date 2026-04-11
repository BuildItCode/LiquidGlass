# Liquid Glass

A Jetpack Compose library for real-time frosted glass, blur, and refractive glass effects. Glass panels sample the live UI behind them — scrolling content, animations, drag gestures — and apply physically-based shader effects with no manual state wiring.

Part of the **Lucid** design system.

---

## Features

- **Live sampling** — glass surfaces capture and refract the pixels under them every frame, including scroll and animation.
- **Physically-based shader** — AGSL glass effect with refraction, chromatic dispersion, lens curvature, and edge rim lighting.
- **Layered glass** — named source layers let you stack glass over glass (e.g. a modal sheet over a glass card over scroll content).
- **API 24+** — full AGSL shader on API 33+ (Tiramisu), hardware `RenderEffect` blur on API 31–32, CPU Stack Blur fallback on API 24–30.
- **Zero recomposition overhead** — implemented as `Modifier.Node`, not composables.
- **Sweep-gradient glass border** — optional `Modifier.glassBorder` for the rim-of-glass highlight you get when light catches a physical edge.

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
    implementation("com.github.BuildItCode:glass:0.0.1")
}
```

---

## Quick start

```kotlin
setContent {
    val manager = rememberBackdropManager(
        defaultScaleFactor = 0.5f,   // capture at 50% resolution
        defaultDebounceMs  = 16L     // ~60fps recapture
    )

    CompositionLocalProvider(LocalBackdropLayerManager provides manager) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. Mark the scrollable background as a source layer.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layeredBackdropSource("background")
            ) {
                LazyColumn { /* content */ }
            }

            // 2. Drop a glass panel over it.
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.Center)
                    .glassBorder(
                        shape = RoundedCornerShape(20.dp),
                        borderColor = Color.White,
                        borderWidth = 1.dp
                    )
                    .layeredBackdropCapture(
                        layerName = "background",
                        shape     = RoundedCornerShape(20.dp),
                        filter    = BackdropFilter.Glass(cornerRadiusDp = 20f)
                    )
            )
        }
    }
}
```

For multi-layer setups, `TriLevelLayout` wires up a background → foreground → overlay stack for you with both `"background"` and `"foreground"` source layers registered automatically.

---

## Filters

| Filter | Description |
|--------|-------------|
| `BackdropFilter.Blur` | Gaussian blur with optional tint. |
| `BackdropFilter.Glass` | Frosted glass with refraction, dispersion, lens curvature, edge rim lighting, and tint. |

See [`glass/.../Glass.md`](glass/src/main/java/com/builditcode/glass/Glass.md) for the full parameter reference, layered glass patterns, freeze/resume captures for modal overlays, and performance notes.

---

## Sample app

The `:app` module in this repo is a showcase — a full-bleed background image sampled through an auto-floating figure-8 glass orb, a layered glass panel, and a row of filter presets comparing `Blur`, `Frost`, and `Lens` side by side. Open it in Android Studio and run on a device or emulator.

---

## Requirements

- **Min SDK:** 24
- **Compose BOM:** 2026.02.01+ (tested)
- **Kotlin:** 2.2+
- **Best experience:** API 33+ for the full AGSL shader. API 31–32 falls back to hardware `RenderEffect` blur. API 24–30 uses a CPU Stack Blur.

---

## License

TBD
