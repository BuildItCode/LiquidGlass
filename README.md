# Liquid Glass

A Jetpack Compose library for real-time frosted glass, blur, and refractive glass effects. Glass panels sample the live UI behind them, including scrolling content, animations, drag gestures, and hardware-backed images, and apply shader effects with no manual state wiring.

Part of the **Lucid** design system.

---

## Features

- **Live sampling** — glass surfaces capture and refract the pixels under them, including scroll, animation, and moving capture regions.
- **Hardware-backed content** — hardware bitmaps and other hardware-rendered Compose content are supported automatically; no caller-provided hardware flag is required.
- **Physically-based shader** — AGSL glass effect with refraction, chromatic dispersion, and edge rim lighting.
- **Layered glass** — named source layers let foreground and overlay glass sample the exact content beneath them, including already-rendered glass from lower layers.
- **API 24+** — GPU layer capture with platform blur and AGSL glass on API 33+, legacy bitmap capture with CPU blur/refraction/edge fallback on API 24-32.
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
    implementation("com.github.BuildItCode:LiquidGlass:0.2.5")
}
```

Or with a version catalog:

```toml
[versions]
glass = "0.2.5"

[libraries]
glass = { group = "com.github.BuildItCode", name = "LiquidGlass", version.ref = "glass" }
```

The Git release tag is `v0.2.5`; the JitPack dependency version is `0.2.5`.

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
                        filter    = BackdropFilter.Glass(
                            shape = RoundedCornerShape(20.dp)
                        )
                    )
            )
        }
    }
}
```

For multi-layer setups, `TriLevelLayout` wires up a background -> foreground -> overlay stack for you with both `TrilevelLayers.Background` and `TrilevelLayers.Foreground` source layers registered automatically. `QuadLevelLayout` adds one more source layer: background -> midground -> foreground -> overlay, exposed through `QuadLevelLayers`. `LiquidScaffold` builds on `QuadLevelLayout` with a state registry so dynamic surfaces can be added to a layer from anywhere that can access the scaffold state.

---

## Filters

| Filter | Description |
|--------|-------------|
| `BackdropFilter.Blur` | Backdrop blur with optional tint and shape-owned clipping. |
| `BackdropFilter.Glass` | Frosted glass with shape-aware refraction, dispersion, edge rim lighting, and optional tint. |

Liquid controls are included for common surfaces: `LiquidSearchBar`, `LiquidButton`, `LiquidToggle`, `LiquidSlider`, and `LiquidCard`. They use the same backdrop capture pipeline with restrained spring press feedback, stable layout bounds, and visible keyboard focus. Slider thumbs follow touch directly and support RTL, keyboard input, and accessibility adjustments. Switches reserve a 48 dp touch target; buttons and search fields can grow with larger text. Search includes an accessible clear action and an optional `onSearch` callback.

See [`glass/.../Glass.md`](glass/src/main/java/com/builditcode/glass/Glass.md) for the full parameter reference, layer structure, modal patterns, API compatibility, and performance notes.

---

## Sample app

The `:app` module in this repo is a verification app for the capture paths: initial hardware image capture, a moving glass card over a static source, a static glass card over a moving source, a moving card over a moving source, and a transparent glass bottom sheet. Open it in Android Studio and run on a device or emulator.

Run the regression tests, sample build, and Android lint checks with:

```shell
./gradlew :glass:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :glass:lintDebug :app:lintDebug
```

Source builds also require Android NDK `27.1.12297006` and CMake `3.22.1` (install through Android Studio's SDK Manager). The AAR includes native blur binaries for arm64-v8a, armeabi-v7a, x86, and x86_64; consuming apps do not need to build C++.

Run `:glass:connectedDebugAndroidTest :app:connectedDebugAndroidTest` on a device to check native pixel parity, concurrent workers, hardware readback threading, and live glass rendering. The JVM blur implementation remains the fallback when the native library cannot load.

On Windows, use `gradlew.bat`. Configure the Android SDK through Android Studio, `ANDROID_HOME`, or the ignored `local.properties` file. The library tests use Robolectric to check CPU blur pixels, scratch-buffer reuse, capture isolation, shader-cache invalidation, and control lifecycle behavior. Shared-source regressions cover 40 consumers, changing background pixels, filter isolation, fractional movement, off-source regions, and consumers disappearing during background preparation. Prepared glass pixels are compared with the previous draw-time processing path. Device testing is still needed to measure frame timing and verify GPU rendering on specific hardware.

Stacked-capture regressions also record a three-source Compose chain into a native hardware canvas, verify background updates through different glass filters, and check bitmap ownership when lower sources disappear or upper captures are cancelled. Native JVM recording checks do not replace device frame-time measurements.

---

## Requirements

- **Min SDK:** 24
- **Compose BOM:** 2026.02.01+ (tested)
- **Kotlin:** 2.2+
- **Best experience:** API 33+ for GPU layer capture, platform blur, and full AGSL glass. API 24-32 uses the legacy bitmap fallback with CPU blur/refraction.
- **Hardware content:** Compose-rendered hardware bitmaps are supported automatically. API 33+ keeps the capture on the GPU; API 28-32 renders recorded hardware pictures and reads back their pixels on a worker. API 29-32 reuses its render surface across captures to avoid per-frame renderer setup; unsupported surfaces use the platform picture snapshot fallback. Older versions, or sources that cannot use that path, fall back to a hardware layer snapshot.
- **Software-only capture:** Pass `disableHardwareAcceleration = true` to `rememberBackdropManager`, `TriLevelLayout`, `QuadLevelLayout`, or `rememberLiquidScaffoldState` to opt out of hardware source capture.

---

## License

MIT
