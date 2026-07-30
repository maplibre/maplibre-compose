# MapLibre Native FFI feedback

Open findings from building the desktop integration in
[DESKTOP_FFI_REWRITE.md](./DESKTOP_FFI_REWRITE.md), kept so they can be
upstreamed to
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi) rather
than left as permanent workarounds here.

This document holds what is still open. An entry leaves it when the fix reaches
a snapshot we resolve and the workaround here is deleted; the deletion is the
record, and git history is where the argument lives. Everything reported during
the first pass — the error model, lifecycle, event-pump, and documentation
entries, and most of the missing APIs — has been fixed upstream and removed from
here.

See [COMMON_API_GAPS.md](./COMMON_API_GAPS.md) for the other direction: things
the FFI already provides that MapLibre Compose has no common API for.

## What belongs here

Two of maplibre-native-ffi's design decisions rule most candidates out, so check
them before adding an entry.

**It exposes MapLibre Native's core concepts, not conveniences.** Anything the
Android and iOS SDKs build in their own language on top of those concepts is a
consumer's job, and therefore ours. Meters-per-pixel and the visible region were
both listed here once and are not anymore: the first is a stateless static in
`mbgl::Projection` that both SDKs simply forward to, and the second has no core
query at all — Android assembles it in Java from four corner projections. See
`metersPerDpAtLatitude` and `getVisibleRegion` in `DesktopMapSession`.

**The binding does not duplicate native validation.** Keeping a dozen call
sites' requirements in sync in Kotlin would be its own bug source, so where
native can reject something and the binding can turn that into a typed
exception, that is the design working. An entry needs something more than "the
binding let it through" — a crash, a silent wrong answer, an untyped exception,
or a diagnostic that does not say what failed.

**Confidence.** Every entry here is marked **verified**: confirmed against the
snapshot by a compiler error, a citation to the native source, or a probe that
executed the behavior. If you add an entry from reading alone, mark it
**reported** so the next pass knows to test it — and expect it to be wrong about
as often as it is right.

Snapshot this was written against: binding `0.1.0-20260730.030702-40`.

## Missing APIs

Each of these forces a local workaround, and each has a
`TODO(maplibre-native-ffi)` at the boundary that names it.

### `GeoJsonSourceOptions` has no `synchronousUpdate` — **verified**

`GeoJsonSourceOptions` carries ten of mbgl's eleven `GeoJSONOptions` fields but
not `synchronousUpdate`, which mbgl reads off the source JSON
(`src/mbgl/style/conversion/geojson_options.cpp:104-109`, stored at
`include/mbgl/style/sources/geojson_source.hpp:39`) and consults when replacing
source data (`geojson_source_impl.cpp:164`). It is the difference between a live
feed that updates in the same frame and one that updates a frame late, so it
matters to exactly the consumers who reach for a typed adder.

_Workaround:_ MapLibre Compose adds every source through `addStyleSourceJson`,
which is the only entry point that can express it. See `Source.attach`.

_Suggested fix:_ add `synchronousUpdate` to `GeoJsonSourceOptions`.

### `StyleImageOptions` cannot carry stretchable content insets — **verified**

`StyleImageOptions` has `pixelRatio` and `sdf` only. mbgl's `style::Image` takes
`stretchX`, `stretchY`, and `content`, which is how a nine-patch background
scales its border without distorting it — the reason `ImageResizeOptions` exists
in the common API.

_Workaround:_ MapLibre Compose uploads the image whole and logs a warning naming
the image, because the alternative is a silently distorted sprite. See
`DesktopStyle.addImage`.

_Suggested fix:_ add the stretch and content fields to `mln_style_image_options`
and `StyleImageOptions`.

### No ambient cache size setter — **verified**

`RuntimeOptions.maximumCacheSize` fixes the limit when the runtime is created
and nothing changes it afterwards; mbgl has
`DatabaseFileSource::setMaximumAmbientCacheSize`. Recreating the runtime is not
the same operation: it stops every download in flight and drops the observers
live packs depend on.

_Workaround:_ MapLibre Compose accepts the call when it matches the configured
limit, warns when nothing was configured, and otherwise throws an
`OfflineManagerException` naming `DesktopRuntimeOptions`. Cross-platform code
routinely calls this at startup and it succeeds on Android and iOS, so failing
silently would be worse than either. See `DesktopOfflineManager`.

_Suggested fix:_ expose `mln_runtime_set_maximum_ambient_cache_size`.

### No offline tile count limit setter — **verified**

mbgl has `setOfflineMapboxTileCountLimit`; the C API does not expose it, so a
desktop download keeps MapLibre's built-in limit whatever the application asks
for.

_Workaround:_ MapLibre Compose logs a warning. The limit is still observed —
exceeding it arrives as `OFFLINE_REGION_TILE_COUNT_LIMIT_EXCEEDED` and becomes
`DownloadProgress.TileLimitExceeded` — so the application can react, it just
cannot choose the number. See `DesktopOfflineManager.setTileCountLimit`.

_Suggested fix:_ expose the setter.
