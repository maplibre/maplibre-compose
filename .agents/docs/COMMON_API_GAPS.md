# Common API gaps

Capabilities MapLibre Native FFI provides that MapLibre Compose has no
cross-platform API for.

This is a staging document: a place to write findings down while they are fresh,
to be converted into issues rather than lived in. Its counterpart tracked what
we wanted _from_ maplibre-native-ffi and is gone, because nothing is left open
against it. This is the other direction — what we could build _with_ it.

## When these land

The sequence they belong to is:

1. ~~Rewrite the desktop platform on maplibre-native-ffi — the branch this
   document came from — and upstream fixes until the result is something we are
   happy with.~~ Done.
2. ~~Once it surpasses the Android and iOS integrations in quality, rewrite
   those on maplibre-native-ffi too.~~ Done: every non-web platform runs on
   maplibre-native-ffi.
3. ~~Redesign the public API and internal architecture around that one native
   integration, deciding along the way whether web folds in via Wasm or stays on
   MapLibre GL JS.~~ Done. Web stays on MapLibre GL JS. The public map API is
   `MapRuntime` and `MapState`.
4. Implement the missing common APIs once — twice at most, if web stays separate
   — against that shared integration.

Everything below is step 4 work. The ownership API decides the objects these
APIs attach to, such as the runtime that owns HTTP and offline work.

The corollary is that **what the FFI can do is the target surface**. Whether the
Android or iOS SDK exposes a capability today does not decide whether it belongs
here; those SDKs are on their way out. Where current availability is noted below
it is context, not a gate.

Found by diffing the public surface of `MapHandle`, `RuntimeHandle`, and
`RenderSessionHandle` against desktop call sites during the desktop rewrite.
Nothing here is a desktop bug.

## Architecture the redesign addressed

Not missing capabilities — shapes in the common layer that the desktop rewrite
had to work around, and that the ownership API was the moment to fix rather than
reproduce. The desktop rewrite exposed them.

**Unloading the outgoing style is a contract no engine states.** Switching a
style has to mark the previous one unloaded, because `LayerManager` skips anchor
validation against an unloaded style, and that is what stops content briefly
composed into the dying node from throwing
`Layer ID '...' not found in base
style` out of the applier. Both engine
sessions do it, by calling `onStyleChanged(this, null)` from `setBaseStyle`, and
both map views time it with a `SideEffect` so the unload precedes the content
subcomposition's inserts; nothing in the common layer requires either. The
redesign makes this the common layer's job rather than a convention each engine
session repeats.

**Offline manager ownership has no lifecycle.** The FFI integration keeps one
process-wide offline manager, dedicated thread, and native runtime, configured
by the first `MlnFfiRuntimeOptions` the process uses and permanent after that.
This preserves downloads when the composable that acquired the manager leaves
composition, because MapLibre holds active download state in memory, but the
runtime can never be reconfigured or closed. The redesign replaces the global
cache with an explicit application-scoped owner: one that can outlive
navigation, can be created once per options value, and can be closed
deliberately when the application is prepared to stop that runtime's active
downloads. Composition-level disposal, weak references, and automatic eviction
are not substitutes because each can silently interrupt a download.

## Style light

Position, color, intensity, and anchor of the style's light source, which fill
extrusions shade against.

- FFI: `setStyleLightJson`, `setStyleLightProperty`, `styleLightProperty`

Naturally a Compose API: a `Light` composable inside `MaplibreMap`'s content,
set the same way layers are.

## Projection mode

Switching between Mercator and globe projections.

- FFI: `projectionMode`

## Style transition options

The style's global transition duration and delay, and whether symbol placement
cross-fades. What every paint property's animation takes its default from, so
this is the one setting that changes how the whole map feels when data updates.

- FFI: `setStyleTransitionOptions`, `styleTransitionOptions`
  ([#465](https://github.com/maplibre/maplibre-native-ffi/pull/465))

Naturally a parameter on `MaplibreMap` or its style content, alongside the other
per-map options.

## HTTP header transforms

Done: `MapRequestInterceptor` on `MapRuntime` / `MapRuntimeOptions`. Native
installs `setHttpHeaderTransform`; web sets `transformRequest` headers. The
install is skipped when the FFI reports the hook as unsupported.

## Missing style images

The event MapLibre raises when a style references a sprite that is not in the
loaded image set, so an application can supply it on demand instead of shipping
every icon up front. The FFI session logs it today and can do nothing else,
because there is no common callback to route it to.

- FFI: the `MAP_STYLE_IMAGE_MISSING` runtime event, paired with the existing
  `setStyleImage`

See the `MAP_STYLE_IMAGE_MISSING` branch in `MlnFfiMapSession.handleEvent`.

## Resource transform

Done: the same `MapRequestInterceptor` rewrites URLs. Native installs
`setResourceTransform`. `MapResourceProvider` is the public serve-bytes API;
`MlnFfiResourceProvider` remains the internal packaged-resource adapter and
composes the user provider in front of `jar:file:` / `file:` reads.

## Offline database merge

Merging a side-loaded offline database into the running one, which is how an
application ships pre-downloaded regions rather than making every user download
them.

- FFI: `startMergeOfflineRegionsDatabase`,
  `takeMergeOfflineRegionsDatabaseResult`

One suspending function on the existing `OfflineManager` interface, so this is
the smallest entry here.

## Map snapshots

- FFI: `requestStillImage`, `readPremultipliedRgba8`, and
  [#282](https://github.com/maplibre/maplibre-native-ffi/pull/282), which adds a
  synchronous render-to-completion so a consumer does not pump the loop frame by
  frame across the language boundary

Already on the roadmap as
[#28](https://github.com/maplibre/maplibre-compose/issues/28), blocked on
decoupling the style API from the `MaplibreMap` composable. Recorded here only
so a future audit does not rediscover it as new.
