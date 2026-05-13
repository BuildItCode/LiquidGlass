# Glass Backdrop Effect

Part of the Lucid design system. A Jetpack Compose toolkit for real-time frosted glass and blur effects: glass panels sample the live UI behind them, including scrolling content, animated content, moving capture regions, and hardware-backed images, and apply shader effects with no manual state wiring required.

## How It Works

The system has two sides:

- **Source** - a `Modifier.Node` that records the pixels that glass surfaces are allowed to sample.
- **Capture** - a `Modifier.Node` that crops the current source capture to its on-screen region, keeps that crop up to date as the source or capture node moves, and applies blur or glass through the best available path for the current API level.

Both modifiers are implemented as `Modifier.Node` (no recomposition overhead). Recapture is driven implicitly by the draw phase: any time a source's `draw()` is re-invoked (e.g. its child content scrolls or animates), the source queues a fresh capture after the manager's debounce interval.

Multiple named layers can coexist. A foreground card can sample a `"Background"` layer, and an overlay sheet can sample a `"Foreground"` layer that contains the background plus non-glass foreground UI.

Glass surfaces can be captured by higher source layers, so blur-over-blur and glass-over-glass layouts are supported. The only blocked case is true same-source feedback, where a capture tries to sample the source currently recording itself.

`TriLevelLayout` exposes its built-in names through `TrilevelLayers`: `Background`, `Foreground`, and `Overlay`. `QuadLevelLayout` exposes `QuadLevelLayers`: `Background`, `Midground`, `Foreground`, and `Overlay`.

### Capture backends

| API level | Capture path | Effect path |
|-----------|--------------|-------------|
| API 33+ | Retained `GraphicsLayer` source capture kept on the GPU. | Platform `RenderEffect` blur and AGSL Glass shader. |
| API 24-32 | Downscaled `Picture` bitmap capture for software-renderable content; automatic hardware snapshot fallback when software capture cannot draw the source. | CPU blur plus CPU refraction/edge/tint fallback for Glass. |

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

Recapture is driven by Compose's draw phase: any redraw of the source subtree (a scrolling `LazyColumn`, an animating child, a state change) re-runs the source's `draw()`, which queues a fresh capture after the manager's debounce interval. You don't need to pass `LazyListState` or any explicit triggers. If the pixels under the source change, a recapture is queued.

If the source contains hardware-backed content such as a Compose image decoded with a hardware bitmap, it is supported automatically. API 33+ keeps the source capture as a GPU layer. API 24-32 starts with the lower-overhead software bitmap path and promotes to a hardware snapshot only when Android reports that the content cannot be drawn into a software canvas.

### 3. Add glass panels

Apply `.layeredBackdropCapture(...)` to the composable you want to render as glass:

```kotlin
Box(Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layeredBackdropSource("Background")
    ) {
        LazyColumn { /* source content */ }
    }

    Box(
        modifier = Modifier
            .size(200.dp)
            .align(Alignment.Center)
            .layeredBackdropCapture(
                layerName = "Background",
                shape     = RoundedCornerShape(20.dp),
                filter    = BackdropFilter.Glass(),
            )
    )
}
```

The capture panel can be captured by a later source layer for blur-over-blur effects. Do not make a capture sample the same source that currently contains it; use a lower source layer for the pixels behind it and a higher source layer for panels above it.

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

Marks this composable as the pixel source for the named layer. The node intercepts `ContentDrawScope.draw()`, draws content to screen normally, and then, if the manager has pending capture requests, records the same draw into the active capture backend.

The on-screen draw always runs first, so a slow or skipped capture never drops a frame of normal rendering.

On API 33+, the source is captured into a retained `GraphicsLayer` and consumed by GPU effects. On API 24-32, software-renderable sources use a downscaled bitmap path; hardware-backed content automatically falls back to a hardware snapshot and is then processed through the legacy bitmap effect path.

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

The capture should sample a source below it. Captures can be included in later source layers, but a capture inside the same source it samples is rendered without recursively sampling itself.

| Parameter | Description |
|-----------|-------------|
| `layerName` | Must match a `layeredBackdropSource` layer name. |
| `shape` | Clip shape for the blur region. |
| `padding` | Inset the blur region if needed. |
| `filter` | The visual effect to apply: `Blur` or `Glass`. |
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
| `gapSize` | Size of the transparent gaps as a fraction of the sweep (0-0.4). |
| `softness` | Feathering width of gap edges (0-0.1). |
| `overlayBrush` | Optional brush drawn over the content (e.g. a white gloss gradient). |

---

## Filter Types

### `BackdropFilter.Blur`

Standard backdrop blur with optional tint.

```kotlin
BackdropFilter.Blur(
    blurRadiusIntensity = 5f,            // 0.0-10.0
    tint = Color.White.copy(alpha = 0.05f)
)
```

**API compatibility:** Platform `RenderEffect` blur on API 33+. API 24-32 uses the legacy CPU Stack Blur fallback.

---

### `BackdropFilter.Glass`

Frosted glass with blur, refraction, edge rim lighting, and optional tint. API 33+ also supports chromatic dispersion through the AGSL shader.

```kotlin
BackdropFilter.Glass(
    blurRadiusIntensity = 3f,    // base blur, 0.0-10.0          (default)
    cornerRadiusDp      = 12f,   // must match the shape corner radius for correct edge refraction
    refraction          = 0.15f, // light bending through thick glass
    dispersion          = 0.12f, // chromatic aberration (RGB splitting)
    edge                = 0.2f,  // white rim-light intensity
    tint                = Color.White.copy(alpha = 0.04f)
)
```

**API compatibility:** Full AGSL shader on API 33+. The AGSL path uses blur, refraction, dispersion, edge, and tint. API 24-32 uses the legacy CPU bitmap fallback with blur, refraction, edge distortion, and tint; dispersion is AGSL-only.

> `cornerRadiusDp` should match the `dp` value used in the `shape` passed to `layeredBackdropCapture` for physically accurate edge refraction. The shader compiles lazily on first draw and is cached on the `Glass` instance, so prefer remembering stable filter instances when the parameters are not changing.

---

## Complete Example

```kotlin
// MainActivity.kt
val backdropManager = rememberBackdropManager(defaultDebounceMs = 16)

CompositionLocalProvider(LocalBackdropLayerManager provides backdropManager) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Source layer: the scrollable background
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
        }

        // Overlay sibling sampling the background source
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.Center)
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
```

---

## Layered Glass

Use named source layers when foreground and overlay surfaces need to sample different parts of the scene, including already-rendered glass:

```kotlin
Box(Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layeredBackdropSource("Background")
    ) {
        LazyColumn { /* ... */ }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .layeredBackdropSource("Foreground")
    ) {
        // Foreground source includes background plus this glass card.
        GlassCard(layerName = "Background")
        ForegroundControls()
    }

    GlassSheet(layerName = "Foreground") // samples background + glass card + controls
}
```

### `LayeredLayout` helper

For nested-layer setups like the one above, the design system ships a small declarative wrapper in `LayeredLayout.kt` that flattens the source composition:

```kotlin
LayeredLayout(modifier = Modifier.fillMaxSize()) {
    layer("Background") { previous ->
        Box(Modifier.fillMaxSize().layeredBackdropSource("Background")) {
            previous()             // nothing on the very first layer
            LazyColumn { /* ... */ }
        }
    }
    layer("Foreground") { previous ->
        Box(Modifier.fillMaxSize().layeredBackdropSource("Foreground")) {
            previous()             // renders the Background layer beneath
            GlassCard(layerName = "Background")
            ForegroundControls()
        }
    }
    layer("Overlay") { previous ->
        Box(Modifier.fillMaxSize()) {
            previous()
            GlassSheet(layerName = "Foreground")
        }
    }
}
```

Each `layer { previous -> ... }` block receives the composed tree of all previously declared layers. A glass surface should sample a lower layer, and a later layer can then capture that already-rendered glass surface.

For common app shells, use `TriLevelLayout` or `QuadLevelLayout` instead of wiring `LayeredLayout` manually. Use `TrilevelLayers` / `QuadLevelLayers` constants for `layerName` values so capture nodes stay aligned with the built-in source names.

---

## Freezing Captures

Most overlays should be modeled as a later layer that samples a source beneath it; in that setup, updates can stay live while the overlay is open.

If you intentionally want a modal sheet, dialog, or popup to render against a static snapshot taken before it appeared, `BackdropLayerManager` exposes two methods:


```kotlin
val manager = LocalBackdropLayerManager.current!!

// When the overlay opens and you want a frozen backdrop:
manager.stopUpdates()

// When the overlay dismisses:
manager.startUpdates()  // resumes captures and triggers a one-shot refresh
```

While `shouldUpdate` is `false`, source nodes skip snapshot recording. Capture nodes keep reading the most recent `CaptureResult`, so existing glass surfaces stay visually stable against the frozen snapshot.

---

## Performance Notes

- **Scale factor**: The single biggest lever. `0.4f` (default) gives a good blur with ~6× fewer pixels to process than full resolution. Drop to `0.3f` for more aggressive savings on heavy scenes.
- **Debounce**: `32ms` is the manager default (~30 fps). Drop to `16ms` for 60 fps recapture if the background animates continuously, or raise it if the background changes rarely.
- **API 33+ capture**: Sources are recorded into retained GPU `GraphicsLayer`s, then filtered with platform blur and AGSL. This avoids CPU bitmap copies for hardware-backed images and videos.
- **API 24-32 fallback**: Software-renderable layers use the lower-overhead `Picture` bitmap path with bitmap reuse. If the source contains hardware-backed content, the layer promotes to a hardware snapshot and then uses the same legacy bitmap effect path.
- **Legacy effects**: `BackdropFilter.Blur` and `BackdropFilter.Glass` both use CPU processing on API 24-32. Legacy Glass keeps blur, refraction, edge distortion, and tint, but dispersion is AGSL-only.
- **Source scope**: Keep the source subtree scoped to the pixels that glass panels actually need. Capturing an entire launcher page at high scale during continuous animation is still real work, even on API 33+.
- **Shader compilation**: `BackdropFilter.Glass` compiles its AGSL shader lazily on first draw and caches it on the filter instance. Prefer stable remembered filter instances when parameters are not changing.
- **`autoInvalidateOnMove`**: When a glass capture node moves on screen, it invalidates *other* layers (excluding its own) so layered sources stay in sync during drag. Disable it if you have many capture nodes that move independently and you do not need other layers to track them.
- **Overscroll**: Disable the stretch/glow overscroll effect when using glass over a `LazyColumn` to avoid visual artifacts: wrap the list in `CompositionLocalProvider(LocalOverscrollFactory provides null)`.
- **Drag state**: Use `mutableFloatStateOf` for drag offsets (`offsetX`, `offsetY`) to avoid boxing `Float` on every pointer event.
- **Modal overlays**: Put modal glass in a later overlay layer that samples the source beneath it. Use `manager.stopUpdates()` / `startUpdates()` only when you intentionally want a frozen backdrop.
