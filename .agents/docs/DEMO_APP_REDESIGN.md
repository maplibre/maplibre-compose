# Demo app redesign

The demo app dates from before the automated test suite. It grew one demo per
feature as a manual smoke test, on a shared mutable `DemoState` that no real
application would use. The tests now cover desktop and web, and will soon cover
mobile, so the app can stop proving that features work and start showing what
MapLibre can do.

The rebuild lands before the API redesign that
[API_REDESIGN.md](./API_REDESIGN.md) stages. The redesign PR then shows its API
changes as a diff over realistic demo code.

The guiding rule: each demo reads as code a user would copy as the starting
point of a real feature.

## Shape of the app

One shared map hosts every demo. Each demo lives at a real place in the world,
and selecting a demo flies the camera there and swaps in that demo's layers and
controls. The shell is adaptive: a bottom sheet on phones, a side panel on
desktop and wide screens.

The Material 3 controls stay visible throughout. The pointer pin button points
toward the selected demo's region when the camera wanders, and tapping it flies
back.

Style is a knob independent of the demo. A demo may declare a preferred style,
which applies once when the demo is selected; a later choice by the user wins.
Every demo tolerates every style without crashing, though some render best on
their preferred one.

### Demo contract

The contract stays minimal, and each demo owns its state internally:

```kotlin
interface Demo {
  val name: String
  val description: String
  val region: BoundingBox
  val preferredStyle: DemoStyle?

  @Composable fun MapContent()

  @Composable fun Panel()
}
```

Selection state lives in the shell. There is no shared demo state and no
visibility flags, unlike the previous `Demo` interface.

### Settings

A separate settings page holds the app-wide diagnostics and toggles:

- gesture options
- overlays: FPS, camera state readout
- Material 3 controls versus stock map controls
- location engine choice, where a platform offers more than one

These were demos before. They are settings because the user wants them available
regardless of which demo is open.

## Demos

Roughly in order of build cost:

1. **3D Manhattan.** The Liberty style with pitch and bearing camera work. Its
   panel includes a control that downloads the region for offline use, which
   exercises the offline API.
2. **Castello Plan overlay.** An `ImageSource` places the 1660 map of New
   Amsterdam over lower Manhattan, with an opacity slider. The image ships as a
   compose resource (it is public domain, and bundling avoids web CORS). The
   four corner coordinates are hand-tuned until landmarks roughly align; the
   Battery and Wall Street are the anchors. NYPL's Map Warper has a georectified
   version to crib coordinates from if rough alignment falls short.
3. **Data visualization.** One dataset, such as earthquakes, with a mode switch
   between points, heatmap, and clusters. The same source rendered three ways
   shows the expression DSL.
4. **Live tracking.** A simulated vehicle follows a route with synchronous
   GeoJSON updates and a camera-follow toggle.
5. **Transit network map.** Routes and stops from static GTFS through
   [mobility-data-kt](https://github.com/sargunv/mobility-data-kt), which does
   not yet support GTFS-RT. The data is the Washington State Ferries feed,
   streamed from WSDOT at runtime. The feed sends no CORS headers, so the demo
   is gated off the web target until a proxy exists.
6. **Real location and orientation.** The location puck with device bearing, and
   the GMS versus platform engine choice on Android. This earns its place
   because automated tests cannot cover real sensors.

## Benchmarks

Benchmarks live on their own route with their own map instance, so demo state
cannot contaminate timing. Each scenario:

1. Prefetches its tiles through the offline manager, so network variance stays
   out of the measurement.
2. Runs a scripted camera or data sequence.
3. Reports average and p95 frame time and dropped frames in a machine-readable
   summary.

Initial scenarios: a zoom pump, a fly-around, and a large-GeoJSON load and
update.

## Build order

Each phase, and each demo within phase 2, merges as its own PR.

- **Phase 0 — raze.** Delete the demo sources down to a hello world: each
  platform entry point launches a `DemoApp()` that shows an empty map with a
  default style. The module structure and platform launchers stay. The
  intermediate source sets (`maplibreNativeShared`, `mlnFfiShared`, and the
  rest) go; the platform differences that motivated them are smaller than they
  were and still shrinking, so the rebuild adds an intermediate set only when a
  demo needs one. `docsnippets/` stays, because it serves the docs site.
- **Phase 1 — skeleton.** The shell, the demo contract, the style knob, the
  pointer pin, and the settings page.
- **Phase 2 — demos.** The six demos above, in order.
- **Phase 3 — benchmarks.** The benchmark route and the initial scenarios.
