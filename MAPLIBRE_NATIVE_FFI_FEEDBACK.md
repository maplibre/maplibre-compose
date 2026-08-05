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
entries, and every one of the missing APIs — has now been resolved upstream and
removed from here, as has the process-exit lifecycle crash. What is left is one
documentation entry.

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

Snapshot this was written against: binding `0.1.0-20260803.074311-52`.

## Documentation

### The Metal borrowed-texture GPU-completion guarantee is undocumented — **verified**

`mln_metal_borrowed_texture_attach` does not say whether a render is complete on
the GPU when `render_update` returns, so a host cannot tell whether it needs a
fence before sampling the texture. Its OpenGL sibling does say
(`include/maplibre_native_c/texture.h:536-539`); the Metal entry point
(`texture.h:394-426`) is silent.

The guarantee does hold. Traced at commit `2c397595`:
`render_session_render_update` (`src/render/render_session_common.cpp:1388`) →
`Renderer::render` → `encoder->present`
(`third_party/maplibre-native/src/mbgl/renderer/renderer_impl.cpp:457`) →
`swap()` (`.../src/mbgl/mtl/command_encoder.cpp:30`) →
`commandBuffer->commit(); commandBuffer->waitUntilCompleted();`
(`src/render/metal/metal_texture_backend.mm:132-143`).

A consumer that assumes the opposite adds a redundant fence; one that assumes it
without checking is right by luck. Either way it should not require reading the
`.mm`.

_Suggested fix:_ document it on the Metal attach entry point, matching the
wording already on the OpenGL one.

## Missing APIs

None open. The four that were listed here are all resolved:

- **`GeoJsonSourceOptions.synchronousUpdate`**, **stretchable image content
  insets on `StyleImageOptions`**, and **a runtime ambient cache size setter**
  all landed in
  [#441](https://github.com/maplibre/maplibre-native-ffi/pull/441). The
  workarounds are deleted; the deletion is the record.
- **The offline tile count limit** was **declined**, and that closes it rather
  than leaving it pending. #441's reasoning: the limit counts only canonical
  Mapbox tile URLs, so it is already a no-op for an ordinary MapLibre style, and
  the native call reports neither completion nor error — the one thing every
  other offline operation is built around.
  `DesktopOfflineManager.setTileCountLimit` now carries a settled explanation
  instead of a `TODO(maplibre-native-ffi)`.

Note that #441 also **removed** `RuntimeOptions.maximumCacheSize`, replacing the
creation-time option with the runtime setter. See `AmbientCacheSizeRequest` for
how a configured budget is applied now that it is an asynchronous operation
rather than part of constructing the runtime.
