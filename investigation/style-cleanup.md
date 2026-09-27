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
