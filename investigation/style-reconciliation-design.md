# Declarative style composition

Implemented model, 2026-09-20. This replaces the initial proposal's queued
post-commit drain: all style inputs now participate in the applier, so no
post-observer scheduling bridge is needed.

## Ownership and commit

```text
Compose evaluates description values
    → applies environment, image, and layer nodes
    → MapNodeApplier.onEndChanges publishes an immutable snapshot
    → one coroutine consumes the latest pending snapshot
    → MapStyleAuthority validates generation and ownership
    → StyleReconciler updates the loaded style
```

The custom composition is bound to one loaded style and inherits its host's
locals and frame clock. It emits an environment node for font/animation scale,
image nodes for property images, and ordered layer nodes with source references.
`onEndChanges` runs after node updates. Neither remember observers nor
SideEffects register style resources anymore. An evaluation abandoned before
apply cannot change installed resources or the previous committed descriptions.

Remembered sources allocate stable IDs and return new description values when
inputs change. A layer's committed source reference carries that value into the
snapshot. Sources are collected from the layers, deduplicating shared objects;
unreferenced descriptions are not installed. Base sources are borrowed and
excluded from application ownership. Different descriptions with conflicting IDs
fail validation. Source order follows committed layer order; maintaining a
separate historical reference order serves no rendering requirement.

Image requests share preparation across properties and layers using the bitmap
or painter and its rendering parameters as the key. Resolved pixels and image
options share one installed image, including results from distinct painters.
Each property commits an image-reference node together with its expression. The
tree determines the active requests; a small cache retains only those requests
and their resolved images after each commit. No acquisition/release reference
counters are needed. Painter effects await a shared result under a per-request
mutex. Cancellation releases the graphics layer; another waiting property can
retry an interrupted preparation. Removing the final reference cancels the
remaining property effects and drops the cache entry.

Painter completion changes Compose state; the next apply commits the image and
its referring property together. A pending property is unset. `imagesPending` is
part of the immutable snapshot. Live maps can apply those snapshots; snapshot
capture waits for a snapshot with all painters resolved. The standalone snapshot
evaluator explicitly sends state-write notifications while driving its own
clock, since it has no UI host to do that work.

## Application and lifetimes

A conflated channel carries snapshots directly to a single consumer. It keeps
the latest pending work without cancelling a mutation already submitted. There
is no apply-generation state, root invalidation key, or presentation effect
waiting for a revision-state recomposition. The returned revision state serves
interaction observation, not scheduling.

MapStyleAuthority accepts snapshots only for their evaluated binding. It checks
again after waiting for an imperative mutation reservation. A replacement loaded
style clears the old declarative ownership, so a base resource can reuse an old
declaration's ID. Mutations from stale evaluations cannot claim it.

Fresh presentations evaluate current content. They do not install an obsolete
snapshot first. A retained engine already holds its installed content; a new
engine receives only the current declarations. Native presentation preparation
resets the content-readiness prerequisite, then ordinary reconciliation releases
it. The old replay API and separate replay-effect barrier have been removed.
Render scheduling and engine-progress rules are unchanged.

The existing reconciler keeps successful installations, dependency ordering,
anchor placement, property batching, and failure recovery. Its mutation path is
now explicitly non-suspending; GeoJSON preparation already lives behind the
backend submission boundary. Runtime orchestration and resource reads can still
suspend. Native source supersession, prepared-data cleanup, URI updates, and
synchronous mode remain the source coordinator's responsibility.

The mechanisms that remain address distinct lifetimes: pre-load style request,
loaded style, installed resource, and physical presentation. Imperative mutation
reservations still protect reentrant callbacks. Resource-read freshness checks
and protected catalog publication still prevent cancelled or obsolete reads from
publishing invalid handles. Those are correctness boundaries, not frame
scheduling devices.

## Validation

Representative coverage includes:

- `StyleCommitTest`: child-only data changes commit in one frame; shared sources
  disappear with their last layer; abandoned evaluation cannot acquire images or
  change source data; disposal does not publish an empty replacement.
- `StyleCompositionLifecycleTest`: the production channel submits a source
  update without advancing another frame after its committing frame. Temporarily
  adding `withFrameNanos {}` before submission makes this test time out; the
  mutation was restored before final validation.
- `SymbolLayerCompositionTest`: keyed image ownership, reorder/removal, sharing
  across properties/layers, identical-pixel deduplication, distinct render
  parameters, and replacement without stale image IDs.
- `StyleImageCacheTest`: equal-pixel lifetime and cancellation while another
  property waits for the same preparation.
- `PainterRenderingTest`: pixel-row capture and graphics-layer release on
  failure.
- `MapPresentationTest`: generation-bound acceptance, declarative/imperative
  ownership, stale handles, reentrant reservations, and resource catalogs.
- Browser lifecycle coverage now asserts that a replacement installs current
  content without first replaying obsolete layers.
- Existing source coordinator, style supersession, snapshotter, layer ordering,
  transitions, and native/browser presentation suites remain in use.

Final command results are reported in the session response. No Pixel
measurements or app deployments are part of this work. The frame test proves the
removal of an extra frame dependency; it does not measure first-visible-pixel
latency.

## Baseline

Analysis started at main `125fc5715c0e93a0c8c91171de16850697dcee55`. Its tree
matches the benchmark baseline `e1b1cf561d0265b6482c810111b9cebfa974146b`
exactly. The original
[Pixel investigation](../../benchmarks/build/benchmarks/pixel-2026-09-20/investigation.md)
reported request-to-submission medians of 24 ms declarative and 1 ms imperative,
with similar parsing times. Those are historical measurements, not measured
performance of this implementation.

## Scope and priorities

The goal is a predictable declarative commit boundary with less implementation
machinery. It is not a new source API or different image reuse behavior. Kotlin
source descriptions carry the ID, data, and options so committed layer
references are sufficient to derive source installation. An ID-only wrapper
would require a separate committed source declaration and lookup. Fresh
descriptions keep speculative evaluation from mutating committed inputs without
that registration mechanism. Kotlin wrapper identity is not a design constraint.

Source and image ownership both participate in the applier commit, so their
integration belongs in the same composition change. Their data preparation
remains separate. Image sharing is retained as useful library behavior, not
removed to reduce line counts. The earlier cross-property duplication was an
unnecessary regression and has been corrected. Public composition/image docs are
restored; implementation details remain here.

Before publication, treat the presentation replay/lifecycle cleanup as a
separate review unit from composition commits unless a concrete dependency
prevents that split. No PR has been published from this workspace.
