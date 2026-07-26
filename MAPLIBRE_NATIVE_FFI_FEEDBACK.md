# MapLibre Native FFI feedback

Running notes on rough edges found while building the desktop integration in
[DESKTOP_FFI_REWRITE.md](./DESKTOP_FFI_REWRITE.md), kept so they can be
upstreamed to
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi) rather
than left as permanent workarounds here.

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
exception, that is the design working. Zero dimensions were listed here once and
are not anymore: `MapHandle.create` and `attachVulkanBorrowedTexture` both throw
`InvalidArgumentException` naming the constraint, measured. An entry needs
something more than "the binding let it through" — a crash, a silent wrong
answer, an untyped exception, or a diagnostic that does not say what failed.

Every entry says what MapLibre Compose does today, so the workaround can be
removed when the upstream fix lands.

**Confidence.** Entries marked **verified** were confirmed directly against the
snapshot — a compiler error, a grep of the native source, or a run. Entries
marked **reported** came from reading the bindings and headers and should be
re-confirmed before filing upstream; a few adjacent claims from the same reading
pass turned out to be wrong, so none of these should be filed unchecked.

Snapshot this was written against: binding `0.1.0-20260725.055919-2`.

---

## Error model

### `renderUpdate()` reports "nothing to draw" as an exception — **verified**

`RenderSessionHandle.renderUpdate()` throws `InvalidStateException` when there
is no pending update. That is the normal steady state of an idle map, not an
error, and it is indistinguishable _by type_ from a genuinely detached or closed
session. The only discriminator is the diagnostic string
`"no map render update is available"`
(`src/render/render_session_common.cpp:993`).

A consumer that treats `InvalidStateException` as an error fails every map on
its first frame, before the style has produced anything. One that swallows the
type entirely spins forever on a dead session.

_Workaround:_ string-match `MaplibreException.diagnostic`.

_Suggested fix:_ return a value rather than throwing — `renderUpdate(): Boolean`
or a `RenderUpdateResult` enum — or give the transient case its own status code
and exception subtype.

### Stale owned-texture frame access throws a raw `IllegalStateException` — **verified**

`OwnedTextureFrameHandleCore.kt:17` guards with `check(!isClosed())`, so using a
released owned-texture frame throws `kotlin.IllegalStateException` rather than a
`MaplibreException` subtype. A consumer that wraps FFI calls in
`catch (e: MaplibreException)` will not catch it, and on a render thread that
can kill the thread and leak the session.

This is a binding concern rather than a validation-duplication one: the binding
has already decided to throw here, and throws a type inconsistent with the rest
of itself. Handles proper behave correctly — using a closed
`RenderSessionHandle` gives
`InvalidStateException: RenderSessionHandle is already closed`, measured.

MapLibre Compose does not exercise this: its hosts render into borrowed
textures, so it never acquires an owned-texture frame.

_Suggested fix:_ throw `InvalidStateException` for consistency with the rest of
the binding.

---

## Lifecycle and ownership

### No JVM cleaner or finalizer — **verified**

`HandleStateCore.LeakReport` is constructed for every handle
(`commonMain/.../lifecycle/HandleStateCore.kt:15,91-111`) but `report()` is
called only from the Kotlin/Native cleaner
(`nativeMain/.../lifecycle/HandleState.kt:22-23`); jvmMain has no `HandleState`
at all. The JVM target does use `java.lang.ref.Cleaner` elsewhere
(`jvmMain/.../render/NativeBuffer.kt:9,16`) but never for `RuntimeHandle`,
`MapHandle`, or `RenderSessionHandle`. If an owner thread dies before `close()`
— an uncaught exception, a cancelled coroutine — the native session, GPU
context, and map leak permanently with no diagnostic: native keeps them in a
process-wide registry with no thread-death hook (`src/runtime/runtime.cpp`).

A merely _dropped_ child handle is as bad as a dead thread. A leaked
`MapHandle`, `RenderSessionHandle`, `OfflineOperationHandle`, or acquired
owned-texture frame means the runtime can never be destroyed and its owner
thread stays bound forever, with no force-close — correctly, since destroying a
runtime with live maps generates use-after-free
(`src/runtime/runtime.cpp:2343-2346`) — and no diagnostic.

_Workaround:_ `try`/`finally` teardown on the owner thread itself.

_Suggested fix:_ register handles with a `java.lang.ref.Cleaner` as a safety
net, logging loudly rather than silently reclaiming, since destruction is
owner-thread-bound and cannot happen on the cleaner thread.

### `detach()` does not release the parent retention — **verified**

A `RenderSessionHandle` retains its `MapHandle` from construction until
`close()`; `detach()` does not release it
(`jvmMain/.../render/RenderSessionHandle.kt:21`, `:44-48`, `:195-202`). The C
API is looser: `mln_map_destroy` rejects only a map that still has an _attached_
session (`map.h:1065-1079`, `src/map/map.cpp:2725`), and
`mln_render_session_detach` clears that link (`src/map/map.cpp:2819-2834`). So a
detached-but-unclosed session blocks `MapHandle.close()` in Kotlin where the C
contract would allow it, failing with `MapHandle has 1 live child handle(s)` — a
count without a culprit, because the Kotlin pre-check short-circuits on an
`AtomicInt` (`HandleStateCore.kt:56-58`) before native's more specific check
runs. Closing the session recovers, so this is a divergence from the documented
contract plus a diagnostic gap, not a deadlock.

_Suggested fix:_ release the parent retention in `detach()` to match the C
contract, or document that `close()` is the only call that releases it.

### `RuntimeHandle.close()` can spin indefinitely — **verified**

Close waits for in-flight resource-provider and resource-transform callbacks
running on network threads, spinning on the owner thread. A provider that blocks
— or that hops to a thread which is itself waiting on the owner thread — turns
teardown into a deadlock or a busy core.

_Suggested fix:_ bound the wait and report a timeout status, or expose a
quiesce-and-cancel entry point to call before close.

### Closing a map silently discards its queued events — **verified**

There is no flush and no terminal event. Any state a consumer mirrors from
events can be permanently stale at close, and a coroutine awaiting a completion
event will never resume.

_Workaround:_ snapshot state synchronously before closing; never await an event
during teardown.

_Suggested fix:_ document this, and consider a drain-on-close option.

---

## Rendering

### Borrowed-texture sessions cannot be resized, and there is no re-attach — **verified**

`RenderSessionHandle.resize()` throws `UnsupportedFeatureException` for borrowed
targets, and there is no re-attach entry point. The only way to follow a host
whose target changed is: close the session, replace the texture, build a new
descriptor, attach again. Since a map permits only one live session, the order
is forced — attach-then-close throws.

This is the single most consequential behavior for a Compose integration,
because a window resize is routine.

_Workaround:_ MapLibre Compose keys a session on `(generation, extent)` and
closes-then-attaches on any change.

_Suggested fix:_ support in-place resize for borrowed targets where the backend
allows it, or add an explicit `reattach(descriptor)` that makes the required
sequence a single call.

### `MapHandle` has no `resize`, and `attach*` resizes as a side effect — **verified**

There is no `MapHandle.resize`; the only `resize` is on `RenderSessionHandle`,
which throws for borrowed targets. The map's size is actually set by
`MapOptions` at creation and then by each `attach*` call, which forwards the
descriptor's extent to `mbgl::Map::setSize`.

Resizing the map as a side effect of attaching a render target is surprising,
and it means there is no way to change the map size without touching the render
session.

_Suggested fix:_ add `MapHandle.setSize`, and document that `attach*` currently
implies it.

### `pixelRatio` is fixed at map creation, and the `scaleFactor` in attach/resize never reaches it — **verified**

`MapOptions.scaleFactor` becomes `mbgl::MapOptions::withPixelRatio` at creation
(`src/map/map.cpp:2702`) and nothing changes it afterwards. `attach*` and
`RenderSessionHandle.resize` both take a `scaleFactor`, but forward only the
logical width and height to `mbgl::Map::setSize`
(`src/render/render_session_common.cpp:901`, `:967`). The session's renderer, by
contrast, _is_ rebuilt from the new scale factor on resize (`:968`, `:1009`), so
after a resize the renderer and the map disagree about pixel ratio, silently.

The map's pixel ratio selects asset density: sprite `@2x`, raster `{ratio}` tile
URLs, and symbol layout. Measured with one borrowed Vulkan session at
`scaleFactor = 2.0` and two maps: `MapOptions.scaleFactor = 1.0` requested
`sprite.json` and `.../0/0/0.png`; `2.0` requested `sprite@2x.json` and
`.../0/0/0@2x.png`. Tile _selection_ was identical — only density differs.
Attaching a session whose scale factor disagrees with the map's is accepted
silently: 60 frames rendered, no exception, no log.

Moving a window between displays of different density therefore yields a
correctly sized framebuffer with the old density's assets, with no error.

The immutability itself is inherited rather than invented here:
`mbgl::Map::Impl::pixelRatio` is `const` (`map_impl.hpp:92`), and Android
(`native_map_view.cpp:70`) and iOS (`MLNMapView.mm:710`) also fix it at
construction and recreate the map to change it.

_Workaround:_ MapLibre Compose recreates the map when the display scale changes.

_Suggested fix:_ reject a `scaleFactor` in `attach*`/`resize` that disagrees
with the map's, so the mismatch is loud, and document that the map's pixel ratio
is immutable. Making it mutable is an upstream mbgl change, not an FFI one.

### `RenderTargetExtent` is logical, the texture is physical, and Vulkan/OpenGL do not check — **verified**

The descriptor extent is in logical pixels while the borrowed texture must be
`ceil(logical * scaleFactor)` physical pixels. A mismatch is not validated and
renders clipped or garbled output rather than throwing. The round trip is also
not exact: `ceil(ceil(p / s) * s)` can exceed `p` by a pixel.

_Suggested fix:_ validate the relationship where the texture dimensions are
known, or expose the expected physical size from the descriptor so callers can
size the texture from it.

---

## Missing APIs

Each of these forces a local reimplementation. Listed roughly by how much pain
the absence causes.

| Missing                                                   | Impact                                                                                                                                                                               | Current workaround                                             |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------- |
| Map size accessor — **verified**                          | Needed to project the corners above; `MapHandle` exposes no size                                                                                                                     | Track logical size in the session and mirror every attach      |
| Animation completion signal — **verified**                | `MAP_CAMERA_DID_CHANGE` fires identically for a jump, a finished ease, a cancellation, and a superseded transition, so a continuation cannot be resolved from it                     | Stamp each request with a generation and wait out the duration |
| Runtime wake / has-pending-work — **verified**            | The owner thread cannot park; it must poll or native loading stalls silently                                                                                                         | Drive the pump from the Compose frame clock                    |
| Clear a resource provider — **verified**                  | The provider is effectively set-once before any map exists                                                                                                                           | Install it during runtime creation, before the map             |
| GeoJSON source options — **verified**                     | `addGeoJsonSourceUrl`/`addGeoJsonSourceData` take no options and there is no `GeoJsonSourceOptions` type, so the typed adders cannot create a clustered source at all                | Add every source through `addStyleSourceJson`                  |
| Typed layer adders — **verified**                         | Only `addColorReliefLayer`, `addHillshadeLayer`, and `addLocationIndicatorLayer` exist; nothing typed for fill, line, circle, symbol, raster, heatmap, fill-extrusion, or background | Add every layer through `addStyleLayerJson`                    |
| Feature extensions reachable from a source — **verified** | `queryFeatureExtension` exists only on `RenderSessionHandle`, so a `GeoJsonSource` cannot reach it without the host's render session                                                 | Thread render-session access through the style binding         |

---

### A non-HTTP URI fails with `invalid authority` — **verified**

The built-in network file source rejects any URI it does not recognize with
`loading style failed: http: invalid authority`. That message names neither the
URI nor the reason, and it is what every consumer sees the first time they point
a style, sprite, glyph, or tile at a packaged resource — which on Compose
Desktop is a `jar:file:` URI, i.e. the common case rather than an exotic one.

_Workaround:_ MapLibre Compose installs a resource provider that intercepts
non-network schemes and reads them itself.

_Suggested fix:_ include the offending URI in the message and say that the
scheme is unsupported. A consumer seeing `invalid authority` has no way to tell
that the fix is to install a resource provider.

---

### Cluster ids must be re-typed unsigned, and the mismatch is silent — **verified**

`queryFeatureExtension(sourceId, feature, "supercluster", …)` reads the cluster
id out of the feature's _properties_, and mbgl looks it up with
`getProperty<uint64_t>` — an exact check against the stored variant alternative
(`render_geojson_source.cpp:15-22`). Every general JSON conversion encodes an
integer as signed, so a `cluster_id` that round-trips through
`queryRenderedFeatures` and back arrives as the wrong alternative.

The mismatch does not fail. The lookup misses, the call returns an empty result,
and the status is OK — indistinguishable from a cluster that genuinely has no
children. The same applies to the `limit` and `offset` arguments for `leaves`: a
signed `limit` is ignored and mbgl silently substitutes its own default of ten,
so the call still returns features and still looks correct.

This is the binding's job rather than mbgl's, and the other two platforms show
why. Android converts the feature out to Java and back, hits exactly this, and
casts it back by hand — twice, in
`platform/android/.../style/sources/geojson_source.cpp:135` and `:154`, from
`double`, because its own conversion picked a third wrong alternative. iOS
sidesteps the id half entirely by never taking the feature out of C++
(`MLNShapeSource.mm:326` passes mbgl its own `GeoJSONFeature` straight back), so
the id keeps its alternative; it still casts `limit` and `offset` by hand at
`:339-340`. Every binding that round-trips a feature through its host language
has to do this, and maplibre-native-ffi is the analogous layer for its
consumers.

_Workaround:_ MapLibre Compose re-types `cluster_id`, `limit`, and `offset` as
`JsonValue.UInt` in a conversion of its own, and a test asserts that `limit = 2`
returns exactly two leaves — the only assertion that distinguishes a correctly
typed limit from an ignored one.

_Suggested fix:_ take the cluster id rather than a whole feature. mbgl reads
nothing else out of it — geometry and identifier are discarded — so the feature
round trip is all risk and no benefit, and an unsigned parameter type makes the
mistake unrepresentable:

```kotlin
public fun getClusterExpansionZoom(sourceId: String, clusterId: ULong): Double
public fun getClusterChildren(sourceId: String, clusterId: ULong): List<Feature>
public fun getClusterLeaves(
  sourceId: String,
  clusterId: ULong,
  limit: ULong,
  offset: ULong,
): List<Feature>
```

The generic `queryFeatureExtension` can stay alongside these for anything else.
Failing that, coerce `cluster_id`, `limit`, and `offset` inside
`queryFeatureExtension` — a special case in a generic function, but better than
every consumer rediscovering this. Failing both, return a distinguishable status
instead of an empty success.

---

### A rendered box query larger than the viewport can return nothing — **verified**

Querying with a `ScreenBox` substantially larger than the map's own extent
returns an empty result rather than everything visible, and only at some
zoom/extent combinations: a 4096dp box over a 512×512 map at zoom 0 answers
normally, while the same box over a larger map at zoom 4 answers with nothing.
Over-covering is the obvious way to write "query everything on screen", so this
is easy to reach and hard to attribute.

_Workaround:_ query with the map's actual extent.

_Suggested fix:_ clamp the query box to the viewport, or document that it must
already be within it.

## Ergonomics and documentation

### Options classes are mutable and lack `equals` — **verified**

`CameraOptions`, `AnimationOptions`, `BoundOptions`, `CameraFitOptions`,
`FreeCameraOptions`, `ViewportOptions`, `TileOptions`, and
`ProjectionModeOptions` are mutable classes without `equals`, `hashCode`, or
`copy`. `map.camera == previous` is reference equality and always false, so a
naive state diff recomposes on every read, and handing one to UI code hands out
a mutable native-facing object.

Confirmed by reading: each is declared `public class`, not `public data class`,
with `var` properties.

_Workaround:_ convert to immutable snapshots on the owner thread.

_Suggested fix:_ make them data classes, or add read-only snapshot types.

### `easeTo(camera, null)` jumps, while `flyTo(camera, null)` animates — **verified**

The header says only that a null animation "uses MapLibre Native's default
animation options", but that default differs per call: `easeTo` defaults to zero
duration and applies instantly (`third_party/.../map/transform.cpp:112`), while
`flyTo` derives a duration from a default velocity of 1.2 screenfuls per second
and genuinely animates (`transform.cpp:330-341`). Measured:
`easeTo(target,
null)` lands on the target before any `runOnce`;
`flyTo(target, null)` has not moved yet.

_Suggested fix:_ say so in the header, on both functions.

### There is no way to restore the unconstrained camera bounds — **verified**

mbgl distinguishes a default-constructed `LatLngBounds` (unbounded: `constrain`
returns its input unchanged — `geo.hpp:104-112`, `geo.cpp:93-96`) from
`LatLngBounds::world()`. `mln_lat_lng_bounds` carries only a southwest/northeast
pair and `to_native_lat_lng_bounds` always builds a bounded hull
(`src/map/map.cpp:2241-2245`), so once bounds are set there is no way back to
the unconstrained state — and `mln_map_get_bounds` reports both states as
-90/-180..90/180 (`map.cpp:2247-2255`), so a caller cannot tell them apart.

Assigning world bounds is not equivalent. Measured on a pristine map, jumping to
longitude 200 wraps to -160 and -250 wraps to 110; after assigning world bounds
the same jumps clamp to 180 and -180, so the map can no longer pan across the
antimeridian. Android's own reset path passes `mbgl::LatLngBounds()`
(`native_map_view.cpp:327-335`), which this API cannot express.

The all-null `BoundOptions()` no-op that led here is not itself a defect: it is
the documented field-mask contract, and mbgl behaves identically.

_Workaround:_ MapLibre Compose assigns explicit world bounds, which silently
costs antimeridian panning.

_Suggested fix:_ add a bounds-clearing field, or an explicit unbounded
representation, to `mln_bound_options` and report it from `mln_map_get_bounds`.

### `CameraOptions.anchor` is always null on read — **verified**

The field exists on the snapshot but is never populated, so code branching on it
is dead.

### Event types are value classes, not enums — **verified**

`RuntimeEventType`, `RuntimeEventSourceType`, `TileOperation`, `RenderMode`,
`NetworkStatus`, `ResourceErrorReason`, and the offline operation kinds are
`value class` wrappers over `Int` — confirmed at `RuntimeEventType.kt:7`,
`public value class RuntimeEventType(public val nativeValue: Int)`. Kotlin
therefore gives no exhaustiveness checking on `when`, and an FFI upgrade that
adds an event type compiles fine and silently falls through.

This may well be deliberate, to let unknown native values pass through. If so it
is worth saying in the KDoc, because the cost is real.

_Workaround:_ explicit translation table with a logged `else`, never
`error("unreachable")`.

### `RuntimeEvent.mapSource` is a weak reference — **verified**

`RuntimeHandle` tracks maps as `mutableMapOf<Long, WeakReference<MapHandle>>`
(`jvmMain/.../runtime/RuntimeHandle.kt:26`, populated at `:333`), and
`toRuntimeEvent` resolves `mapSource` through it at `:350`. So if the consumer
does not hold a strong reference to its `MapHandle`, GC can turn `mapSource`
null and make every map event unattributable — an intermittent,
memory-pressure-dependent failure where the map simply stops repainting.

_Suggested fix:_ document it, or carry a stable map id alongside the handle so
attribution does not depend on liveness.

### `pollEvent()` is not a pure read — **reported**

On `MAP_STYLE_LOADED` it makes native calls on the source map to reap detached
custom geometry sources. So it can throw from the map rather than the runtime,
and skipping it while a map is live leaks binding-side state. Neither is
discoverable from the name or signature.

### `runOnce()` drains the whole queue — **verified**

`runtime.h:705` says "Runs one pending owner-thread task for this runtime",
which understates it substantially. The chain is `mln_runtime_run_once` ->
`RunLoop::runOnce()` -> `uv_run(impl->loop, UV_RUN_NOWAIT)`
(`run_loop.cpp:144-148`), and that iteration invokes the async handler
`RunLoop::process()`, which is a `while (true)` draining both the high-priority
and default queues until empty (`run_loop.hpp:118-135`). A caller budgeting for
one bounded task can be blocked for the length of a style parse.

The documented behavior would also be a problem in its own right: pumping one
task per frame would couple MapLibre's progress to the display rate. It is worth
knowing which of the two the API intends to promise.

_Suggested fix:_ correct the documentation.

### Camera events carry animated-vs-immediate in an undocumented field — **verified**

`MAP_CAMERA_WILL_CHANGE` and `MAP_CAMERA_DID_CHANGE` put the mbgl
`CameraChangeMode` in `RuntimeEvent.code`, which the public header does not
mention. Useful, but not safe to depend on as written.

### `setStyleUrl` leaks tracked custom-source state that `setStyleJson` clears — **reported**

Switching styles by URL does not clear the binding's `CustomGeometrySourceState`
and its upcall stubs; switching by JSON does. The asymmetry is invisible.

### Style load failures arrive only as events — **reported**

`setStyleUrl`/`setStyleJson` never throw for a bad style; the failure comes back
as `MAP_LOADING_FAILED`. Correct for an async load, but worth stating in the
KDoc of the setters, since the natural assumption is the opposite.

### Camera types live in `camera`, not `map` — **verified**

`CameraOptions`, `AnimationOptions`, `BoundOptions`, `CameraFitOptions`, and
`EdgeInsets` are in `org.maplibre.nativeffi.camera`, while the methods taking
them are on `MapHandle` in `org.maplibre.nativeffi.map`. Reasonable, but it
costs a lookup; cross-references in the KDoc would help.
