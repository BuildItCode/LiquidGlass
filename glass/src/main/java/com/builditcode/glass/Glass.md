# Glass Backdrop Effect

Part of the Lucid design system. A Jetpack Compose toolkit for real-time frosted glass and blur effects: glass panels sample the live UI behind them — including scrolling content, animated content, moving capture regions, and hardware-backed images — and apply physically-based shader effects with no manual state wiring required.

## How It Works

The system has two sides:

- **Source** — a `Modifier.Node` that starts with a fast downscaled `Picture` capture and automatically promotes that layer to a `GraphicsLayer` snapshot if Android reports that the scene contains hardware-backed content that cannot be drawn into a software canvas.
- **Capture** — a `Modifier.Node` that crops the current snapshot to its on-screen region, keeps that crop up to date as the source or capture node moves, and applies blur or glass through the best available path for the current API level.

Both modifiers are implemented as `Modifier.Node` (no recomposition overhead). Recapture is driven implicitly by the draw phase: any time a source's `draw()` is re-invoked (e.g. its child content scrolls or animates), the source queues a fresh capture after the manager's debounce interval.

Multiple named layers can coexist. A glass card can sample a `"Background"` layer (the scrolling content behind it) and a `"Combined"` layer (the full screen including other glass cards), enabling layered glass-on-glass effects.

---

## Setup

### 1. Create the manager

In your root composable (e.g. `MainActivity`), create and provide a `BackdropLayerManager`:

```kotlin
val backdropManager = rememberBackdropManager(
    defaultScaleFactor = 0.4f,   // capture at 40% resolution (performance vs quality)
    defaultDebounceMs  = 16L     // minimum ms between full re-captures (~60fps)
)

CompositionLocalProvider(LocalBackdropLayerManager provides backdropManager) {
    // your app content
}
```

### 2. Mark source layers

Add `.layeredBackdropSource("LayerName")` to any composable whose visual content should be captured:

```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .layeredBackdropSource("Background")
) {
    LazyColumn { /* scrollable content */ }
}
```

Recapture is driven by Compose's draw phase: any redraw of the source subtree (a scrolling `LazyColumn`, an animating child, a state change) re-runs the source's `draw()`, which queues a new downscaled capture after the manager's debounce interval. You don't need to pass `LazyListState` or any explicit triggers — if the pixels under the source change, a recapture is queued.

If the source contains hardware-backed content such as a Compose image decoded with a hardware bitmap, the source promotes itself to the hardware snapshot path automatically. The caller does not need to mark a source as "hardware".

### 3. Add glass panels

Apply `.layeredBackdropCapture(...)` to the composable you want to render as glass:

```kotlin
Box(
    modifier = Modifier
        .size(200.dp)
        .layeredBackdropCapture(
            layerName = "Background",
            shape     = RoundedCornerShape(20.dp),
            filter    = BackdropFilter.Glass(),
        )
)
```

---

## API Reference

### `rememberBackdropManager`

```kotlin
@Composable
fun rememberBackdropManager(
    defaultScaleFactor: Float = 0.4f,
    defaultDebounceMs: Long   = 32L
): BackdropLayerManager
```

| Parameter | Description |
|-----------|-------------|
| `defaultScaleFactor` | Resolution scale for internal bitmaps. `0.4` = 40% of screen size. Lower = faster, blurrier captures. |
| `defaultDebounceMs` | Minimum delay between full re-captures. `16` = every frame at 60fps. `32` = every other frame. |

---

### `Modifier.layeredBackdropSource`

```kotlin
fun Modifier.layeredBackdropSource(layerName: String): Modifier
```

Marks this composable as the pixel source for the named layer. The node intercepts `ContentDrawScope.draw()`, draws content to screen normally, and then — if the manager has pending capture requests — records the same draw into a downscaled snapshot. The source starts on the lower-overhead software path and automatically promotes itself to the hardware-capable `GraphicsLayer` snapshot path if Android reports that the content cannot be drawn into a software canvas.

The on-screen draw always runs first, so a slow or skipped capture never drops a frame of normal rendering.

---

### `Modifier.layeredBackdropCapture`

```kotlin
fun Modifier.layeredBackdropCapture(
    layerName: String,
    shape: Shape                = RoundedCornerShape(12.dp),
    padding: PaddingValues      = PaddingValues(0.dp),
    filter: BackdropFilter      = BackdropFilter.Blur(),
    autoInvalidateOnMove: Boolean = true
): Modifier
```

Samples the named source layer at this composable's screen position and renders the chosen filter through it.

| Parameter | Description |
|-----------|-------------|
| `layerName` | Must match a `layeredBackdropSource` layer name. |
| `shape` | Clip shape for the blur region. |
| `padding` | Inset the blur region if needed. |
| `filter` | The visual effect to apply — `Blur` or `Glass`. |
| `autoInvalidateOnMove` | When `true`, moving this composable triggers a refresh of other layers. Keeps layered effects in sync during drag. |

---

### `Modifier.glassBorder`

```kotlin
fun Modifier.glassBorder(
    shape: Shape,
    borderColor: Color,
    borderWidth: Dp,
    gapSize: Float  = 0.15f,
    softness: Float = 0.05f,
    overlayBrush: Brush? = null
): Modifier
```

Draws a sweep-gradient border with gaps at the top-right and bottom-left corners (to mimic light catching the edge of physical glass). Combine with `layeredBackdropCapture` for a complete glass panel.

| Parameter | Description |
|-----------|-------------|
| `gapSize` | Size of the transparent gaps as a fraction of the sweep (0–0.4). |
| `softness` | Feathering width of gap edges (0–0.1). |
| `overlayBrush` | Optional brush drawn over the content (e.g. a white gloss gradient). |

---

## Filter Types

### `BackdropFilter.Blur`

Standard Gaussian blur.

```kotlin
BackdropFilter.Blur(
    blurRadiusIntensity = 5f,            // 0.0–10.0
    tint = Color.White.copy(alpha = 0.05f)
)
```

**API compatibility:** Full hardware blur on API 33+. API 31–32 uses a hardware `RenderEffect` blur over the captured bitmap via `RenderNode`. API 24–30 uses the CPU Stack Blur fallback.

---

### `BackdropFilter.Glass`

Full physically-based frosted glass shader with refraction, chromatic dispersion, and edge rim lighting.

```kotlin
BackdropFilter.Glass(
    blurRadiusIntensity = 3f,    // base blur, 0.0–10.0          (default)
    cornerRadiusDp      = 12f,   // must match the shape corner radius for correct edge refraction
    refraction          = 0.15f, // light bending through thick glass
    curve               = 0.2f,  // convex lens bulge
    dispersion          = 0.12f, // chromatic aberration (RGB splitting)
    saturation          = 1.0f,  // background colour vibrancy
    contrast            = 1.0f,  // background dynamic range
    edge                = 0.2f,  // white rim-light intensity
    tint                = Color.Transparent
)
```

**API compatibility:** Full AGSL shader on API 34+. API 31–33 uses a CPU glass fallback for refraction, dispersion, saturation/contrast, and rim lighting. API 24–30 uses the CPU Stack Blur fallback.

> `cornerRadiusDp` should match the `dp` value used in the `shape` passed to `layeredBackdropCapture` for physically accurate edge refraction. The shader compiles lazily on first draw and is cached on the `Glass` instance, so allocating a new `Glass()` per recomposition is cheap.

---

## Complete Example

```kotlin
// MainActivity.kt
val backdropManager = rememberBackdropManager(defaultDebounceMs = 16)

CompositionLocalProvider(LocalBackdropLayerManager provides backdropManager) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Source layer — the scrollable background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layeredBackdropSource("Background")
        ) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // content items...
                }
            }

            // Glass card sampling the scrollable background
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .glassBorder(
                        shape       = RoundedCornerShape(20.dp),
                        borderColor = Color.White,
                        borderWidth = 1.dp
                    )
                    .layeredBackdropCapture(
                        layerName = "Background",
                        shape     = RoundedCornerShape(20.dp),
                        filter    = BackdropFilter.Glass(cornerRadiusDp = 20f)
                    )
            ) {
                Text("Hello glass")
            }
        }
    }
}
```

---

## Layered Glass (Glass Over Glass)

To have one glass card sample another glass card's output, use a second source layer that wraps both:

```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .layeredBackdropSource("Combined") // captures everything below, including Card 1
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layeredBackdropSource("Background") // captures only the scroll content
    ) {
        LazyColumn { /* ... */ }

        GlassCard(layerName = "Background") // Card 1 samples the scroll layer
    }

    GlassCard(layerName = "Combined") // Card 2 samples the full scene including Card 1
}
```

### `LayeredLayout` helper

For nested-layer setups like the one above, the design system ships a small declarative wrapper in `LayeredLayout.kt` that flattens the nesting:

```kotlin
LayeredLayout(modifier = Modifier.fillMaxSize()) {
    layer("Background") { previous ->
        Box(Modifier.fillMaxSize().layeredBackdropSource("Background")) {
            previous()             // nothing on the very first layer
            LazyColumn { /* ... */ }
            GlassCard(layerName = "Background")
        }
    }
    layer("Combined") { previous ->
        Box(Modifier.fillMaxSize().layeredBackdropSource("Combined")) {
            previous()             // renders the Background layer beneath
            GlassCard(layerName = "Combined")
        }
    }
}
```

Each `layer { previous -> ... }` block receives the composed tree of all previously declared layers and is responsible for placing it inside its own `layeredBackdropSource`. The result is the same as hand-nesting the boxes, but easier to read when you have three or more layers.

---

## Freezing Captures (Modal Overlays)

When a modal sheet, dialog, or popup opens *over* a glass surface, you usually want it to render against a snapshot of the screen taken **before** the overlay appeared — otherwise the overlay's own content gets sampled into its own blur and the effect collapses into a feedback loop.

`BackdropLayerManager` exposes two methods for this:

```kotlin
val manager = LocalBackdropLayerManager.current!!

// When the overlay opens:
manager.stopUpdates()

// When the overlay dismisses:
manager.startUpdates()  // resumes captures and triggers a one-shot refresh
```

While `shouldUpdate` is `false`, the source nodes short-circuit snapshot recording and `BackdropState.requestCapture` is a no-op. Capture nodes keep reading the most recent `CaptureResult`, so existing glass surfaces stay visually correct against the frozen snapshot.

---

## Performance Notes

- **Scale factor**: The single biggest lever. `0.4f` (default) gives a good blur with ~6× fewer pixels to process than full resolution. Drop to `0.3f` for more aggressive savings on heavy scenes.
- **Debounce**: `32ms` is the manager default (~30 fps). Drop to `16ms` for 60 fps recapture if the background animates continuously, or raise it if the background changes rarely.
- **Adaptive capture**: Software-renderable layers stay on the lower-overhead `Picture` path with bitmap reuse. Layers that contain hardware-backed content promote once to the `GraphicsLayer` snapshot path, without a caller-provided flag.
- **Hardware snapshots**: The hardware path is meant for Compose-rendered hardware content such as hardware bitmaps. Keep the source subtree scoped to the pixels that glass panels actually need; capturing an entire launcher page at high scale during continuous animation is still real work.
- **API 31-33 Glass fallback**: `BackdropFilter.Glass` uses a cached CPU fallback for the non-blur glass terms on API 31-33 because the AGSL runtime shader path is reserved for API 34+. Plain `BackdropFilter.Blur` still uses hardware `RenderEffect` on API 31+.
- **Shader compilation**: `BackdropFilter.Glass` compiles its AGSL shader lazily on first draw and caches it on the instance, so allocating a new `Glass()` per recomposition is cheap.
- **`autoInvalidateOnMove`**: When a glass capture node moves on screen, it invalidates *other* layers (excluding its own) so layered glass-on-glass stays in sync during drag. Disable it if you have many capture nodes that move independently and you don't need layered effects to track them.
- **Overscroll**: Disable the stretch/glow overscroll effect when using glass over a `LazyColumn` to avoid visual artifacts: wrap the list in `CompositionLocalProvider(LocalOverscrollFactory provides null)`.
- **Drag state**: Use `mutableFloatStateOf` for drag offsets (`offsetX`, `offsetY`) to avoid boxing `Float` on every pointer event.
- **Modal overlays**: Always pair `manager.stopUpdates()` / `startUpdates()` with the open/close lifecycle of any sheet that sits over a glass surface — see *Freezing Captures*.
