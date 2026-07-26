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

**Confidence.** Every remaining entry is marked **verified**: confirmed against
the snapshot by a compiler error, a citation to the native source, or a probe
that executed the behavior. Nothing here is unchecked reading.

Getting there deleted six entries. Three were factually wrong, and three were
true but out of scope under the rules above. Two more turned out to understate
their bug rather than overstate it. If you add an entry from reading alone, mark
it **reported** so the next pass knows to test it — and expect it to be wrong
about as often as it is right.

Snapshot this was written against: binding `0.1.0-20260725.055919-2`.

## Resolved upstream

Entries deleted because maplibre-native-ffi fixed them. Kept as a list so it is
clear what has already been absorbed, and so a workaround here can be removed
when the fix reaches a snapshot we resolve.

| Was                                                                                                               | Fixed by                                                         | State                                                                                                                                            |
| ----------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `renderUpdate()` reported "nothing to draw" by throwing, distinguishable only by its diagnostic string            | [#338](https://github.com/maplibre/maplibre-native-ffi/pull/338) | **merged 2026-07-26** — our workaround stays until a snapshot carries it; see `NO_RENDER_UPDATE_DIAGNOSTIC`                                      |
| A rendered box query larger than the viewport returned nothing rather than everything                             | [#339](https://github.com/maplibre/maplibre-native-ffi/pull/339) | open                                                                                                                                             |
| Option types had no `equals`, so a state diff on `map.camera` always recomposed                                   | [#342](https://github.com/maplibre/maplibre-native-ffi/pull/342) | open                                                                                                                                             |
| `RenderTargetExtent` was logical while the texture was physical, with the rounding rule undocumented and no check | [#343](https://github.com/maplibre/maplibre-native-ffi/pull/343) | open — descriptors now state the physical size, the session rejects a mismatch, and `mln_render_target_extent_physical_size` exposes the formula |

Not a fix but worth tracking:
[#340](https://github.com/maplibre/maplibre-native-ffi/pull/340) documents the
unsigned-JSON contract for supercluster queries without changing it, and
[#282](https://github.com/maplibre/maplibre-native-ffi/pull/282) adds a
synchronous still-render primitive relevant to
[COMMON_API_GAPS.md](./COMMON_API_GAPS.md).

---

---

## Upstream triage

Every finding below has been through maplibre-native-ffi's own triage. That was
the point of the document, so what remains is a record rather than a queue: the
prose entries are kept only where this repository still carries a workaround
whose removal depends on the outcome.

**Accepted, fix in flight.** Stale owned-texture frame throwing a raw
`IllegalStateException` (.NET has the same bug); no JVM cleaner or finalizer —
confirmed as a spec violation, with JVM and Android the only bindings that do
not report leaks; `detach()` retaining the parent, which turned out wider than
reported and affects Kotlin, Rust, Go, and Python; and the `invalid authority`
diagnostic, whose message turned out to be theirs rather than mbgl's.

Documentation fixes in the same wave: `runOnce()` draining the queue, closing a
map discarding its queued events, the `easeTo`/`flyTo` default split — which
affects all six animated entry points, not the two reported — the style-setter
failure split, `CameraOptions.anchor` being input-only, the `pollEvent()` side
effect, the `mapSource` weak reference, and event value classes being
open-domain by design.

**Accepted, queued.** `mln_map_get_size`, `mln_runtime_clear_resource_provider`,
`MLN_BOUND_OPTION_UNBOUNDED`, GeoJSON source options, an animation completion
signal, and `mln_camera_change_mode` with an event `code`/`payload` table.

**Rejected, and worth not re-filing.** Typed layer adders: JSON-first insertion
is stated policy, and the three typed adders that exist are for raster-DEM
validation and the location indicator's per-frame setters rather than the start
of a set. Feature extensions reachable from a source: renderer-scoped in mbgl
and not movable, which is why Android threads a renderer frontend into every
source peer. Also declined: drain-on-close for map events, a bounded-wait
timeout on close, a has-pending-work predicate — not truthfully implementable,
since the uv loop carries timers and fd watches — and a blocking run.

**Where this report was wrong.** `RuntimeHandle.close()` does not spin; it
blocks on a mutex, and the wait is documented on the callback typedef. The
resource-provider half was unfounded. There is a real bug underneath, which they
found rather than us: `destroy_runtime` holds the process-global registry mutex
across that wait, so one slow transform callback stalls every `mln_*` call in
the process. The `pixelRatio`/`scaleFactor` entry was written against an older
head and is already fixed by #343, which added both the mismatch warning and the
documentation.

One structural correction to how this document described the project: it assumed
bindings that do not exist. There are seven — dotnet, go, kotlin, python, rust,
swift, and zig.

---

## Error model

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

### Closing a map silently discards its queued events — **verified**

There is no flush and no terminal event. Any state a consumer mirrors from
events can be permanently stale at close, and a coroutine awaiting a completion
event will never resume.

_Workaround:_ snapshot state synchronously before closing; never await an event
during teardown.

_Suggested fix:_ document this, and consider a drain-on-close option.

---

## Rendering

## Missing APIs

Each of these forces a local reimplementation. Listed roughly by how much pain
the absence causes.

| Missing                                    | Impact                                                                                                                                                                   | Current workaround                                             |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------- |
| Map size accessor — **verified**           | Needed to project the corners above; `MapHandle` exposes no size, and the `attach*` docs do not mention that attaching sets it, so there is nothing to read it back from | Track logical size in the session and mirror every attach      |
| Animation completion signal — **verified** | `MAP_CAMERA_DID_CHANGE` fires identically for a jump, a finished ease, a cancellation, and a superseded transition, so a continuation cannot be resolved from it         | Stamp each request with a generation and wait out the duration |
| Clear a resource provider — **verified**   | The provider is effectively set-once before any map exists                                                                                                               | Install it during runtime creation, before the map             |
| GeoJSON source options — **verified**      | `addGeoJsonSourceUrl`/`addGeoJsonSourceData` take no options and there is no `GeoJsonSourceOptions` type, so the typed adders cannot create a clustered source at all    | Add every source through `addStyleSourceJson`                  |

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

## Ergonomics and documentation

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

### `pollEvent()` is not a pure read — **verified**

On `MAP_STYLE_LOADED` the binding calls back into the source map:
`toRuntimeEvent` invokes `MapHandle.releaseDetachedCustomGeometrySources()`
(`jvmMain/.../runtime/RuntimeHandle.kt:353`), which makes one
`mln_map_get_style_source_type` call per tracked custom geometry source and
closes the upcall stubs of those the new style dropped
(`jvmMain/.../map/MapHandle.kt:709-718`). Nothing says so: the C contract for
`mln_runtime_poll_event` describes only popping the queue (`runtime.h:718-747`),
and the Kotlin declaration carries no KDoc.

It is also the only place that state is released for the `setStyleUrl` path — an
inline style can be cleared eagerly because it parses synchronously, while a URL
style's old sources stay live until the fetch completes — so the release is
conditional on the event being polled and on `mapSource` still resolving through
the runtime's `WeakReference`.

Two things this is _not_. It cannot throw in practice:
`map_get_style_source_type` only validates the map handle and reports
`found = false` for an unknown id (`src/map/map.cpp:3039-3067`), and a closed
map is unregistered before it could be seen. And the loop is empty for a
consumer that adds no custom geometry sources, which is MapLibre Compose today.

_Suggested fix:_ document the side effect on `pollEvent`.

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

### Style load failure reporting splits by setter, and only the C header says so — **verified**

`setStyleJson` throws for a malformed style _and also_ enqueues
`MAP_LOADING_FAILED`; `setStyleUrl` never throws for a bad style and reports
only through the event. Measured against a live map:

| call                                                                    | result                                                                                                                                   |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `setStyleJson("{ this is not json")`                                    | throws `NativeErrorException: Failed to parse style: Missing a name for object member.`, then `MAP_LOADING_FAILED` with the same message |
| `setStyleJson("[1,2,3]")`                                               | throws `NATIVE_ERROR (-5): Failed to parse style: style must be an object`, then `MAP_LOADING_FAILED`                                    |
| `setStyleJson` with `"version": 42`, or a layer naming a missing source | no throw, no failure event, `MAP_STYLE_LOADED` — only a log line                                                                         |
| `setStyleUrl("jar:file:/nope.json")`                                    | no throw; `MAP_LOADING_FAILED` `loading style failed: http: invalid authority`                                                           |
| `setStyleUrl("file:///missing.json")`                                   | no throw; `MAP_LOADING_FAILED` `loading style failed:` (empty reason)                                                                    |
| `setStyleUrl(<404>)`                                                    | no throw; `MAP_LOADING_FAILED` `loading style failed: HTTP status code 404`                                                              |

The split follows mbgl — `Style::Impl::loadJSON` parses inline
(`third_party/.../style/style_impl.cpp:46`) while `loadURL` fetches first
(`:55`) — and the C layer turns a failure recorded during the call into
`MLN_STATUS_NATIVE_ERROR` (`src/map/map.cpp:2846-2853`, `:2866-2882`).
`map.h:1081-1116` states all of it, down to "Malformed JSON can fail
synchronously and still enqueue a loading-failed event". The Kotlin `expect`
declarations state none of it, and the natural assumption from the signature is
that neither setter throws — which is how MapLibre Compose came to call
`setStyleJson` unguarded inside its draw pass, where a malformed
`BaseStyle.Json` escaped the frame instead of being reported as a load failure.

The event is the only copy of the failure: it is one-shot, and the per-map
message the runtime keeps (`src/runtime/runtime.cpp:2582-2584`) has no getter in
any header. That matches mbgl, which exposes `getLastError()` only on
`Style::Impl`, so the mobile SDKs are event-only too — a getter would be out of
scope to request.

_Suggested fix:_ mirror the header's wording into the Kotlin KDoc for both
setters.

### Camera types live in `camera`, not `map` — **verified**

`CameraOptions`, `AnimationOptions`, `BoundOptions`, `CameraFitOptions`, and
`EdgeInsets` are in `org.maplibre.nativeffi.camera`, while the methods taking
them are on `MapHandle` in `org.maplibre.nativeffi.map`. Reasonable, but it
costs a lookup; cross-references in the KDoc would help.
