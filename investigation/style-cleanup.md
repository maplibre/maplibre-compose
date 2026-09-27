# Style ownership cleanup

## Design

The committed Compose tree emits one immutable, engine-ready style snapshot.
Layer nodes own the previous values of their image properties. A
composition-local image registry prepares shared requests after commit and
notifies the tree when pixels are ready. Pending image replacements keep their
prior property value; unrelated source and layer changes can be submitted
immediately.

This removes the intermediate declaration model, the declaration-to-revision
resolver coroutine, its completion channel, and its separate map matching
previous properties to layer registrations. Removed nodes cannot inherit another
node's pending property state. Image job identity still guards late
uncancellable results.

The live composition owns image preparation and disposes synchronously. Accepted
immutable snapshots may finish on the engine owner after the presentation
closes. The map authority serializes acceptance through handle publication.

Each source or layer installation owns its current definition. The reconciler
owns membership and placement, without duplicating definitions. Unchanged layer
updates skip compatibility filtering and JSON reconstruction.

Generation-bound handles remain access capabilities. Engine summaries avoid full
JSON reads. Caller-side anchor preparation remains necessary because anchor
predicates are application callbacks. Source snapshots remain independent of
live Compose state and graphics resources.

## Execution and validation

1. Move image work into the committed node tree and adapt live/snapshot
   consumers.
2. Consolidate installation state and remove repeated unchanged-layer work.
3. Replace resolver-specific fixtures with behavior tests; remove fixture-only
   disposal coverage after folding it into the production lifecycle regression.
4. Independently review speculative composition, inline image completion, stale
   preparation, style replacement, cancellation, and snapshot readiness.
5. Run targeted tests, one JVM OpenGL suite, and static checks. Use focused
   mutation probes to establish regression sensitivity. Measure unchanged-layer
   update work before and after; do not infer device FPS from a microbenchmark.

No Android/device tests or full backend matrix. Preserve public behavior and the
existing synchronous-close Apple assertion.

## Results

The declaration model and resolver are removed. `StyleSnapshot` is the tree's
output; each layer definition derives identity and construction properties from
its JSON. Installation objects own their current definition. Generation-bound
handles, caller-side anchor preparation, and the acceptance-through-publication
lock retain their distinct responsibilities.

The focused tests and full JVM OpenGL suite passed, as did `mise run check`.
Mutating synchronous disposal and inline image registration ordering caused the
corresponding regression tests to fail. Independent reviews found no correctness
blocker. The existing Apple synchronous-close assertion is unchanged; iOS was
not run locally.

An interleaved JVM probe of repeated identical 40-property layer updates
measured 2,608 allocated bytes per update before the cleanup and zero afterward.
Median time was 796 ns versus 2 ns, but the latter permits JIT elimination of
repeated no-op work; this is not a rendering or full-reconciliation benchmark.
The temporary probe was removed. Source snapshot reuse and cached construction
properties additionally avoid work outside that probe.

Production Kotlin shrank by 19 lines; test Kotlin grew by 40. The reduction in
ownership states is more substantial than the line-count reduction. Obsolete
late-result simulation and fixture-only disposal coverage were replaced with
checks of preparation cancellation, complete snapshot readiness, and the real
composition lifetime.
