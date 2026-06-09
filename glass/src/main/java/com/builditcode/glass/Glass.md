# Glass Backdrop Effect

Part of the Lucid design system. A Jetpack Compose toolkit for real-time frosted glass and blur effects: glass panels sample the live UI behind them, including scrolling content, animated content, and moving capture regions, and apply shader effects with no manual state wiring required.

## How It Works

The system has two sides:

- **Source** — `Modifier.layeredBackdropSource(name)` captures the pixels behind glass surfaces into a downscaled, double-buffered bitmap and publishes it to a shared `BackdropLayerManager`.
- **Capture** — `Modifier.layeredBackdropCapture(name, ...)` reads the published capture live in its own draw phase, positions it under its on-screen region, and applies blur or glass.

The capture reads its layer's published capture in composition, so a publish recomposes and redraws the consumer; its on-screen position is read in draw so scrolling stays frame-aligned. A consumer redraws when the source publishes new pixels — a static source publishes rarely, an animated one each frame — and it re-samples at its own live position every frame as it scrolls, with no lag and no manual triggers. (A `Modifier.Node` that observed/invalidated entirely in draw was tried but a static consumer over an animating source froze: the source writes the capture during its own draw phase, which a draw-time consumer can't reliably react to — composition observation is the dependable path.)

Multiple named layers can coexist. A foreground card can sample a `"Background"` layer, and an overlay sheet can sample a `"Foreground"` layer that contains the background plus non-glass foreground UI.

Glass surfaces drawn into a higher source layer can themselves be sampled, so blur-over-blur and glass-over-glass layouts work. The only blocked case is true same-source feedback: a capture must not sample the source that contains it.

`TriLevelLayout` exposes its built-in names through `TrilevelLayers`: `Background`, `Foreground`, and `Overlay`. `QuadLevelLayout` exposes `QuadLevelLayers`: `Background`, `Midground`, `Foreground`, and `Overlay`.

### Effect backends

| API level | Effect path |
|-----------|-------------|
| API 33+   | Source sampled live and filtered on the GPU: platform `RenderEffect` blur and the AGSL Glass shader (refraction, dispersion, edge, tint). |
| API 24-32 | Source cropped and filtered on the CPU: Stack Blur plus CPU refraction/edge/tint for Glass (dispersion is AGSL-only). |

> The source is always captured into a software bitmap, so source content must be software-renderable. For images, decode without a hardware bitmap (for example Coil's `allowHardware(false)`).

---

## Setup

### 1. Create the manager

In your root composable (e.g. `MainActivity`), create and provide a `BackdropLayerManager`:

```kotlin
val backdropManager = rememberBackdropManager(
    defaultScaleFactor = 0.5f,   // capture at 50% resolution (performance vs quality)
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

Recapture is driven by Compose's draw phase: any redraw of the source subtree (a scrolling `LazyColumn`, an animating child, a state change) re-runs the source's draw and republishes its pixels. You don't need to pass `LazyListState` or any explicit triggers — if what's under the source changes, the next draw captures it.

The source is captured into a software bitmap, so its content must be software-renderable. If you display images, decode them without a hardware bitmap (for example Coil's `allowHardware(false)`); a hardware bitmap cannot be drawn into the software capture canvas.

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
    defaultScaleFactor: Float = 0.5f
): BackdropLayerManager
```

| Parameter | Description |
|-----------|-------------|
| `defaultScaleFactor` | Resolution scale for capture bitmaps. `0.5` = 50% of source size. Lower = faster, blurrier captures. |

---

### `Modifier.layeredBackdropSource`

```kotlin
fun Modifier.layeredBackdropSource(layerName: String): Modifier
```

Marks this composable as the pixel source for the named layer. It draws content to screen normally, then captures the same draw into a downscaled, double-buffered bitmap and publishes it to the manager.

The on-screen draw always runs first, so the capture never drops a frame of normal rendering. The capture is a software bitmap on every API level, so source content must be software-renderable.

---

### `Modifier.layeredBackdropCapture`

```kotlin
fun Modifier.layeredBackdropCapture(
    layerName: String,
    shape: Shape?               = null,
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
| `shape` | Optional clip shape override. If omitted, `filter.shape` is used for both clipping and shader edge math. |
| `padding` | Inset the blur region if needed. |
| `filter` | The visual effect to apply: `Blur` or `Glass`. |
| `autoInvalidateOnMove` | When `true`, the component re-samples its layer as it moves so the crop tracks its new position. |

---

### `Modifier.glassBorder`

```kotlin
fun Modifier.glassBorder(
    shape: Shape,
    borderColor: Color,
    borderWidth: Dp,
    gapSize: Float  = 0.15f,
    softness: Float = 0.05f,
    overlayBrush: Brush? = null,
    rotationDegrees: Float = 0f
): Modifier
```

Draws a sweep-gradient border with gaps at the top-right and bottom-left corners (to mimic light catching the edge of physical glass). Combine with `layeredBackdropCapture` for a complete glass panel.

For an opt-in device-motion rim, remember a sensor-backed rotation value and pass it to the border:

```kotlin
val borderRotation = rememberGlassBorderGyroscopeRotation()

Modifier.glassBorder(
    shape = shape,
    borderColor = Color.White,
    borderWidth = 1.dp,
    rotationDegrees = borderRotation
)
```

| Parameter | Description |
|-----------|-------------|
| `gapSize` | Size of the transparent gaps as a fraction of the sweep (0-0.4). |
| `softness` | Feathering width of gap edges (0-0.1). |
| `overlayBrush` | Optional brush drawn over the content (e.g. a white gloss gradient). |
| `rotationDegrees` | Extra rotation applied to the sweep highlight. Use `0f` for a static border, or `rememberGlassBorderGyroscopeRotation()` for opt-in device-motion rotation. |

---

## Filter Types

### `BackdropFilter.Blur`

Standard backdrop blur with optional tint.

```kotlin
BackdropFilter.Blur(
    radius = 20.dp,                       // blur radius
    tint  = Color.White.copy(alpha = 0.05f),
    shape = RoundedCornerShape(16.dp)
)
```

**API compatibility:** Platform `RenderEffect` blur on API 33+. API 24-32 uses the CPU Stack Blur fallback.

---

### `BackdropFilter.Glass`

Frosted glass with blur, refraction, edge rim lighting, and optional tint. API 33+ also supports chromatic dispersion through the AGSL shader.

```kotlin
BackdropFilter.Glass(
    shape = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomEnd = 12.dp,
        bottomStart = 12.dp
    ),
    blurRadiusIntensity = 3f,    // base blur, 0.0-10.0          (default)
    refraction          = 0.15f, // light bending through thick glass
    dispersion          = 0.12f, // chromatic aberration (RGB splitting)
    edge                = 0.2f,  // white rim-light intensity
    tint                = Color.White.copy(alpha = 0.04f)
)
```

**API compatibility:** Full AGSL shader on API 33+. The AGSL path uses blur, refraction, dispersion, edge, and tint. API 24-32 uses the legacy CPU bitmap fallback with blur, refraction, edge distortion, and tint; dispersion is AGSL-only.

> On API 33+, `RoundedCornerShape` resolves to four independent shader corner radii, so asymmetric corners affect refraction and rim lighting. Arbitrary path shapes still clip correctly, but the AGSL edge math falls back to rectangular radii. The shader compiles lazily on first draw and is cached on the `Glass` instance, so prefer remembering stable filter instances when the parameters are not changing.

---

## Complete Example

```kotlin
// MainActivity.kt
val backdropManager = rememberBackdropManager()

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
                    filter    = BackdropFilter.Glass(
                        shape = RoundedCornerShape(20.dp)
                    )
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

### `LiquidScaffold`

`LiquidScaffold` wraps `QuadLevelLayout` with a state object that can register dynamic content into any layer:

```kotlin
val scaffoldState = rememberLiquidScaffoldState()
var modalHandle by remember { mutableStateOf<LiquidScaffoldComponentHandle?>(null) }

LiquidScaffold(
    modifier = Modifier.fillMaxSize(),
    state = scaffoldState,
    background = { HomeWallpaper() },
    midground = { HomeContent() },
    foreground = { Dock() }
)

Button(
    onClick = {
        modalHandle?.remove()
        modalHandle = scaffoldState.addComponent(layer = QuadLevelLayers.Overlay) {
            GlassModal(layerName = QuadLevelLayers.Foreground)
        }
    }
) {
    Text("Open")
}
```

Keep the returned handle and call `remove()` when the surface closes or the owner is disposed. Use `state.addComponent(key = ..., layer = ...)` when an owner may re-register the same surface and should replace the previous entry instead of adding a duplicate. Content inside `LiquidScaffold` can also read `LocalLiquidScaffoldState.current`.

---

## Liquid Components

The library includes ready-made glass controls that share the same interaction behavior: a small spring scale, slight bounce, and brightness lift while pressed or dragged.

```kotlin
LiquidSearchBar(
    value = query,
    onValueChange = { query = it },
    placeholder = "Search apps",
    borderRotationDegrees = rememberGlassBorderGyroscopeRotation()
)

LiquidButton(
    text = "Continue",
    onClick = ::continueFlow
)

LiquidToggle(
    checked = enabled,
    onCheckedChange = { enabled = it }
)

LiquidSlider(
    value = intensity,
    onValueChange = { intensity = it }
)

LiquidCard(
    onClick = ::openDetails
) {
    Text("Glass content")
}
```

By default these controls render without backdrop capture, which makes them usable in previews, dialogs, and ordinary Compose layouts. Pass `layerName = QuadLevelLayers.Background` or another source layer when the control is inside a layered glass scene and should sample live content behind it. Each component accepts `blurRadiusIntensity` for the glass blur strength and `borderRotationDegrees` for rim rotation; pass `rememberGlassBorderGyroscopeRotation()` when you want the rim highlight to react to device motion. `LiquidButton` and `LiquidCard` also have slot content for custom layouts.

---

## Freezing Captures

Most overlays should be modeled as a later layer that samples a source beneath it; in that setup, updates can stay live while the overlay is open.

If you intentionally want a modal sheet, dialog, or popup to render against a static snapshot taken before it appeared, `BackdropLayerManager` exposes two methods:


```kotlin
val manager = LocalBackdropLayerManager.current!!

// When the overlay opens and you want a frozen backdrop:
manager.stopUpdates()

// When the overlay dismisses:
manager.startUpdates()  // resumes captures
```

While `shouldUpdate` is `false`, sources skip publishing new captures. Capture surfaces keep sampling the most recent published image, so existing glass stays visually stable against the frozen snapshot.

---

## Performance Notes

- **Scale factor**: The single biggest lever. `0.5f` (default) blurs with ~4× fewer pixels than full resolution. Drop to `0.3f`-`0.4f` for more aggressive savings on heavy scenes.
- **API 33+ effects**: The source bitmap is sampled live and filtered with platform blur and the AGSL shader on the GPU. The built `RenderEffect` is cached and only rebuilt when the size or filter parameters change.
- **API 24-32 effects**: `BackdropFilter.Blur` and `BackdropFilter.Glass` are processed on the CPU. The cropped + blurred result is cached and only recomputed when the sampled image, region, or filter changes. Glass keeps blur, refraction, edge, and tint; dispersion is AGSL-only.
- **Source scope**: Keep the source subtree scoped to the pixels that glass panels actually need. Capturing an entire page at high scale during continuous animation is still real work, even on API 33+.
- **Shader compilation**: the AGSL Glass shader compiles lazily on first draw. Prefer stable, remembered filter instances when parameters are not changing so the cached effect can be reused.
- **Overscroll**: Disable the stretch/glow overscroll effect when using glass over a `LazyColumn` to avoid visual artifacts: wrap the list in `CompositionLocalProvider(LocalOverscrollFactory provides null)`.
- **Drag state**: Use `mutableFloatStateOf` for drag offsets (`offsetX`, `offsetY`) to avoid boxing `Float` on every pointer event.
- **Modal overlays**: Put modal glass in a later overlay layer that samples the source beneath it. Use `manager.stopUpdates()` / `startUpdates()` only when you intentionally want a frozen backdrop.
