# Liquid Glass

A Jetpack Compose library for real-time frosted glass, blur, and refractive glass effects. Glass panels sample the live UI behind them — scrolling content, animations, drag gestures, and hardware-backed images — and apply physically-based shader effects with no manual state wiring.

Part of the **Lucid** design system.

---

## Features

- **Live sampling** — glass surfaces capture and refract the pixels under them, including scroll, animation, and moving capture regions.
- **Hardware-backed content** — sources automatically promote from the low-overhead software capture path to a hardware snapshot path when the scene contains hardware-accelerated content such as hardware bitmaps.
- **Physically-based shader** — AGSL glass effect with refraction, chromatic dispersion, and edge rim lighting.
- **Layered glass** — named source layers let you stack glass over glass (e.g. a modal sheet over a glass card over scroll content).
- **API 24+** — GPU layer capture with AGSL glass on API 33+, legacy CPU bitmap fallback with refraction and edge distortion on API 24–32.
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
    implementation("com.github.BuildItCode:LiquidGlass:0.1")
}
```

Or with a version catalog:

```toml
[versions]
glass = "0.1"

[libraries]
glass = { group = "com.github.BuildItCode", name = "LiquidGlass", version.ref = "glass" }
```

The Git release tag is `v0.1`; the JitPack dependency version is `0.1`.

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
| `BackdropFilter.Glass` | Frosted glass with refraction, dispersion, and edge rim lighting. |

See [`glass/.../Glass.md`](glass/src/main/java/com/builditcode/glass/Glass.md) for the full parameter reference, layered glass patterns, freeze/resume captures for modal overlays, and performance notes.

---

## Sample app

The `:app` module in this repo is a verification app for the capture paths: initial hardware image capture, a moving glass card over a static source, a static glass card over a moving source, and a moving card over a moving source. Open it in Android Studio and run on a device or emulator.

---

## Requirements

- **Min SDK:** 24
- **Compose BOM:** 2026.02.01+ (tested)
- **Kotlin:** 2.2+
- **Best experience:** API 33+ for GPU layer capture, platform blur, and full AGSL glass. API 24–32 uses the legacy CPU bitmap fallback.
- **Hardware content:** Compose-rendered hardware bitmaps are supported automatically; no caller-provided hardware flag is required.

---

## License

MIT
