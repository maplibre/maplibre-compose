# Map API redesign

## Problem Statement

The map API must support durable map state, temporary UI presentation, style
composition, imperative style mutation, snapshots, runtime resources, and
controlled platform access. These concerns have different lifetimes. Treating
them as one state object creates ambiguous ownership and invalid intermediate
states.

One logical map can have at most one UI presentation. Native platforms can
retain the engine map without a presentation. Web must recreate its GL JS map
and replay the desired state. Attachment, detachment, closure, and platform
events therefore need one atomic lifecycle model despite the different platform
implementations.

Declarative style content must work with both interactive maps and snapshots.
Snapshot capture must not retarget or interrupt an interactive map. Imperative
style handles must identify one loaded style generation, because a base-style
reload invalidates the resources that those handles address.

The public API must express these ownership and lifetime boundaries directly.
Backward compatibility is not a constraint. The design favors a small and
coherent interface over compatibility shims.

## Solution

Use separate runtime, map, presentation, style-composition, snapshotter, and
platform-access modules. Each module has one lifetime and a narrow interface.

An explicit `MaplibreRuntime` owns shared cache, resource, HTTP, and offline
configuration. It creates and tracks `MapState` and `MapSnapshotter` children. A
process-owned default runtime keeps ordinary Compose call sites short.
Applications use an explicit runtime when they need custom configuration or
deterministic shutdown.

One `MapState` represents one map. Native platforms create its map when the
first engine operation needs it and retain that map across UI detachment. Web
creates a GL JS map for each presentation and replays the desired state. One
`MapState` supports at most one attached `MaplibreMap`.

A single serialized lifecycle module controls map creation, attachment,
detachment, closure, and platform-event acceptance. Each attachment receives an
opaque render lease. Only the current lease can publish presentation state or
detach the map.

`MapState` exposes durable desired state and a nullable `MapPresentation`. The
presentation contains the current viewport, camera operations, projections,
gesture state, and rendered-feature queries. Presentation operations never wait
for a future attachment.

`StyleComposition` is a reusable declarative definition. A map and a snapshotter
can evaluate the same definition in separate Compose compositions. An evaluator
exists only while the object has a consumer. It publishes an immutable
desired-style revision. Detachment disposes the evaluator but keeps the last
revision on the retained native map. A later attachment evaluates the definition
again and reconciles the complete revision.

`MapSnapshotter` owns a separate non-UI map. Each capture supplies immutable
size, camera, and output environment values. Snapshot work never replaces or
retargets the map inside a `MapState`.

## User Stories

1. As an application developer, I want a map to survive temporary removal from
   composition, so that navigation and adaptive layouts do not reload it.
2. As an application developer, I want one state object to represent one map, so
   that resource identity is predictable.
3. As an application developer, I want a second UI attachment to fail
   immediately, so that two viewports cannot compete for one map.
4. As an application developer, I want the native map to retain its loaded style
   and camera while detached, so that reattachment is inexpensive.
5. As a Web application developer, I want a recreated GL JS map to restore
   desired state, so that unavoidable browser recreation preserves application
   behavior.
6. As an application developer, I want a durable camera position on `MapState`,
   so that the next presentation starts at the intended position.
7. As an application developer, I want viewport-dependent camera operations on
   the current presentation, so that their availability is explicit.
8. As an application developer, I want camera fits to use the current viewport,
   so that the result cannot use dimensions from a departed surface.
9. As an application developer, I want camera animations to belong to one
   presentation, so that detachment cannot transfer an animation to a later
   attachment.
10. As an application developer, I want projections to belong to the current
    presentation, so that a cached transform cannot describe a replaced
    viewport.
11. As an application developer, I want rendered-feature queries to require a
    presentation, so that a detached query has no ambiguous target.
12. As an application developer, I want observable map values to use Compose
    snapshot state, so that UI recomposes after accepted changes.
13. As an application developer, I want suspending engine operations to accept
    calls from any coroutine context, so that I do not need platform-thread
    knowledge.
14. As an application developer, I want desired configuration writes to return
    immediately, so that configuration remains easy to use from Compose and
    ViewModels.
15. As an application developer, I want load and reconciliation state to report
    engine progress, so that a desired write does not imply completed rendering.
16. As an application developer, I want one reusable style-composition
    definition, so that an interactive map and a snapshotter can render the same
    declarative content.
17. As an application developer, I want style state shared between composition
    roots to be hoisted, so that snapshots and interactive maps receive explicit
    shared inputs.
18. As an application developer, I want a detached map to keep its last applied
    style revision, so that detachment does not remove composed layers from the
    retained native map.
19. As an application developer, I want reattachment to evaluate the current
    external state, so that changes made while detached appear before the next
    presentation renders.
20. As an application developer, I want base-style selection to be desired
    configuration, so that a map can record the next style without claiming that
    it has loaded.
21. As an application developer, I want persistent application layers and
    sources in the style composition, so that a base-style reload reconciles
    them declaratively.
22. As an application developer, I want imperative style changes to target one
    loaded generation, so that their lifetime is explicit.
23. As an application developer, I want a stale style handle to fail clearly, so
    that it cannot mutate a replacement style.
24. As an application developer, I want immutable layer and source definitions,
    so that I can reuse them across maps and snapshotters.
25. As an application developer, I want live handles to contain opaque style
    identity, so that I cannot combine an ID with the wrong generation.
26. As an application developer, I want a reusable snapshotter, so that repeated
    snapshots can reuse one non-UI map.
27. As an application developer, I want each capture to provide its camera and
    output size, so that snapshot configuration has no hidden mutable state.
28. As an application developer, I want snapshots to use the declarative style
    composition, so that still images match application-owned style content.
29. As an application developer, I want snapshot work to remain independent from
    an interactive map, so that capture cannot interrupt a UI render session.
30. As an application developer, I want one runtime to own maps, snapshots,
    cache configuration, and offline work, so that their resource lifetime is
    consistent.
31. As an application developer, I want a shared default runtime, so that a
    basic application does not need runtime setup.
32. As an application developer, I want an explicit runtime option, so that
    applications can select cache and resource configuration.
33. As an application developer, I want runtime closure to close its maps and
    snapshotters, so that no child uses released shared resources.
34. As an application developer, I want map closure to reject new work
    immediately, so that closure has one observable commit point.
35. As an application developer, I want to await physical cleanup when required,
    so that tests and application shutdown can verify resource release.
36. As a desktop application developer, I want presentation-host configuration
    to remain window-scoped, so that changing a GPU context does not replace the
    application runtime.
37. As a Web application developer, I want the common runtime interface, so that
    common code can create map state consistently.
38. As a Web application developer, I want unsupported runtime operations to
    fail explicitly, so that missing GL JS capabilities are visible.
39. As an advanced application developer, I want controlled platform-map access,
    so that I can use engine capabilities that the common interface does not
    expose.
40. As a library maintainer, I want lifecycle behavior behind one interface, so
    that a transition change has one implementation and one test surface.
41. As a library maintainer, I want platform callbacks tagged with opaque
    identity, so that stale events are rejected uniformly.
42. As a library maintainer, I want each implementation stage to be complete and
    useful, so that no temporary scaffolding depends on a later repair.

## Implementation Decisions

- Public compatibility is not a design constraint. Use the smallest interface
  that serves the domain.
- `MaplibreRuntime` is an application-scoped module. Runtime options contain
  cache, resource, HTTP, and offline configuration.
- The shared default runtime is process-owned. A child created through the
  default runtime does not close that runtime.
- Explicit runtimes support custom configuration and deterministic closure.
- A runtime tracks every child that it creates. Runtime closure rejects new
  children and closes existing children. Child closure does not close the
  runtime.
- Web implements the common runtime interface. Operations that GL JS cannot
  support throw `UnsupportedOperationException` initially.
- The desktop presentation host remains separate from the runtime because it has
  window and GPU context scope. Rename it to describe presentation rather than
  map ownership.
- `MapState` represents exactly one map. It exposes ownership, closure, desired
  camera position, style state, and the current presentation.
- Native map allocation is lazy. The first operation that requires a native map
  creates it. The map remains alive until state or runtime closure.
- Web creates a GL JS map for each presentation and replays desired state into
  it.
- A `MapState` accepts one presentation at a time. A rival attachment throws
  before changing logical or physical state.
- One serialized lifecycle module owns the complete transition. Platform
  adapters do not maintain a second authoritative render-slot state.
- The lifecycle model has the following states:

  ```text
  Uninitialized -> Detached -> Attaching(lease) -> Attached(lease)
       |              |                               |
       +--------------+-------------------------------+
                              |
                           Closing -> Closed
  ```

- An opaque render lease identifies an attachment. Events, operations, and
  detachment name that lease. Work from a departed lease has no effect.
- Lifecycle cleanup is non-cancellable after its commit point. Camera animation
  arbitration can use cancellation because replacement is valid behavior in that
  domain.
- Public engine operations are suspending and marshal to the module that owns
  the platform map.
- Public engine-derived values are read-only snapshot state.
- Synchronous writes are limited to desired configuration. They publish desired
  values and schedule reconciliation without claiming engine completion.
- `close()` publishes logical closure and schedules physical cleanup.
  `awaitClosed()` waits for composition, presentation, map, and runtime
  resources to finish cleanup.
- `MapPresentation` exists only for the current render lease. It contains
  viewport state, camera movement operations, camera movement reporting,
  projections, gestures, and rendered-feature queries.
- `MapState` exposes the durable camera position. Camera movement operations
  belong to `MapPresentation`.
- Presentation operations never wait for a later attachment. A cached
  presentation fails after its lease departs.
- `StyleComposition` is a public reusable value that contains only declarative
  style content. Base style and imperative mutation remain on the map-like
  object's style module.
- Each map or snapshotter evaluates a `StyleComposition` in its own Compose
  composition. Internal `remember` state and effects are not shared between
  evaluators.
- The initial implementation creates the evaluator only while a consumer exists.
  Consumer loss disposes the evaluator and preserves the last immutable
  desired-style revision.
- Evaluator disposal does not apply an empty revision to a retained map. A later
  consumer creates a new evaluator and reconciles its complete revision.
- Continuous detached evaluation is a lower-priority extension. It must preserve
  the same public `StyleComposition` contract.
- A base-style reload drops imperative style mutations and invalidates every
  live handle. The style composition defines persistent application content.
- Layer and source definitions are immutable reusable values. Live layer and
  source handles belong to one loaded style identity.
- An opaque style identity replaces separate style-generation,
  binding-generation, and ID authority parameters.
- `MapSnapshotter` is a runtime-owned, non-UI map. It has no
  presentation-attachment interface.
- A snapshotter creates its map on the first capture and can retain it for later
  captures.
- Each capture request contains immutable output size, camera, density, and
  layout-direction inputs.
- A snapshot evaluator uses the same `StyleComposition` definition as an
  interactive map when the caller supplies that value to both objects.
- `MapState` does not initially expose capture. Future same-map capture is
  acceptable only when the texture rendering mode can produce it without
  replacing or interrupting the active presentation.
- Delicate platform access uses a suspending lambda. The platform handle does
  not escape the call. Native access is available after lazy map creation,
  including while detached. Web access requires an attached presentation.
- Implement the design in this order:
  1. Isolate the `StyleBinding` collapse.
  2. Add explicit runtime ownership, runtime configuration, and one internal
     lifecycle authority.
  3. Add typed render leases and the retained-native and Web-replay presentation
     adapters.
  4. Publish the breaking `MapState`, `MapPresentation`, and `StyleComposition`
     interface and migrate callers.
  5. Add `MapSnapshotter`.
  6. Add generation-bound imperative handles, runtime capabilities, and delicate
     platform access in focused changes.
- Every stage implements a complete invariant. A stage does not depend on a
  later stage for correctness.

## Testing Decisions

- Tests assert observable behavior through the highest available interface. They
  avoid locks, queues, callback fields, generation counters, and
  platform-adapter internals.
- The lifecycle authority is the primary test seam. Tests drive attachment,
  detachment, closure, event delivery, and rival attachment through that
  interface.
- A fake platform adapter records accepted commands and emits lease-tagged
  events. Tests verify that stale leases cannot change state.
- Scenario tests cover every valid lifecycle transition and every refused
  transition.
- A rival attachment test verifies that refusal changes neither the existing
  presentation nor the platform session.
- Detachment tests verify that presentation state becomes unavailable before a
  later lease can publish.
- Closure tests verify immediate logical closure, refusal of new work, child
  cleanup, and `awaitClosed()` completion.
- Cancellation tests verify that cleanup completes after the lifecycle commit
  point.
- Camera tests verify durable position restoration and presentation-scoped
  animation, fit, and gesture behavior.
- Presentation tests verify that cached presentations fail after detachment and
  never target a later lease.
- Style evaluator tests treat the immutable desired-style revision as the
  interface. They verify complete reconciliation without inspecting Compose
  runtime machinery.
- Detachment tests verify that evaluator disposal preserves the last applied
  revision on a retained native map.
- Reattachment tests verify that a fresh evaluator reconciles current external
  state before the map presents the new revision.
- Style reload tests verify that composition-owned content returns and
  imperative handles become stale.
- Definition tests verify that immutable layer and source definitions can be
  used by two independent maps.
- Handle tests verify that a live handle cannot mutate another style generation
  or another map.
- Native integration tests verify retained map identity across presentation
  replacement.
- Browser integration tests verify GL JS recreation and replay across
  presentation replacement.
- Snapshotter tests verify repeated captures, immutable per-capture
  configuration, independent map ownership, style-composition evaluation,
  timeout, cancellation, and closure.
- Runtime tests verify shared default behavior, explicit configuration, child
  tracking, closure order, and rejection after closure.
- Desktop integration tests verify that replacing a presentation host does not
  replace or close the runtime.
- Web tests verify explicit failures for unsupported runtime operations.
- Platform-access tests verify owner-thread execution and prevent use of the
  platform handle after the lambda returns.
- Tests that depend on real rendering run in the existing live-map platform
  source sets. Common lifecycle and desired-state tests use fake adapters.
- Regression tests must prove sensitivity by disabling or bypassing the
  production invariant when a narrow defect would otherwise permit a false
  positive.

## Out of Scope

- Compatibility shims for superseded APIs.
- Two simultaneous UI presentations for one `MapState`.
- Waiting or queueing presentation operations until a future attachment.
- Continuous detached style evaluation in the initial implementation.
- Sharing one running Compose composition between an interactive map and a
  snapshotter.
- Snapshot capture through `MapState` in the initial implementation.
- Retargeting or pausing an interactive render session to produce a still image.
- Identical native and Web resource lifetimes.
- Native offline behavior on GL JS. Unsupported common runtime operations throw
  initially.
- Persistent imperative mutations across a base-style reload.
- Raw platform handles that remain valid after the delicate access call returns.
- A combined application-runtime and desktop-presentation-host object.
- Delivery of the complete redesign as one change.

## Further Notes

The implementation sequence keeps the complete target design visible while
limiting each delivery stage to one coherent module or contract. Small stages
make each invariant easier to verify.

The style-composition evaluator is a necessary complex module. Its interface
contains its recomposer, frame clock, snapshot observation, environment, error
reporting, and shutdown behavior.

The design treats native retention as the primary behavior. Web replay is an
explicit adapter for a platform that cannot detach a GL JS map from its
rendering context.
