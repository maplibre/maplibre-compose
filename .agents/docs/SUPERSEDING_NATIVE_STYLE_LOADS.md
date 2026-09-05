# Superseding native style documents

## Decision

Apply the latest requested base style once, after handling a complete native
runtime event batch. Do not wait for the preceding style document to finish.
Keep the same native map, renderer, camera, lifecycle authority, and resource
provider. Intermediate requests that have not reached native may be skipped.

The request identity changes immediately when the application assigns a style.
Old bindings are invalidated at that point. The native event producer changes
only at the end of the drain, immediately before the native setter. Events
already emitted by the preceding setter therefore retain its identity. A setter
called reentrantly by a lifecycle callback only schedules work for a later
drain. A nested assignment during invalidation takes precedence over its outer
call.

A failed event drain must fail the runtime rather than cross the style boundary
with unhandled events. A malformed JSON setter also queues a failure event; that
event is handled before another setter can run.

## Native evidence

Inspected the published FFI `0.202609.0` source at
[`a6439089482f035c60cfbbbe2f1c048f9280eeff`](https://github.com/maplibre/maplibre-native-ffi/tree/a6439089482f035c60cfbbbe2f1c048f9280eeff)
and its embedded Native revision `2a8ebc490` on 2026-09-05:

- `src/map/map.cpp`: style setters run on the map owner thread. The observer
  queues `MAP_STYLE_LOADED` and `MAP_LOADING_FAILED` synchronously from the
  style callbacks. The latter comes from style errors, not ordinary tile errors.
- `third_party/maplibre-native/src/mln/style/style_impl.cpp`: `loadJSON` resets
  `styleRequest` before parsing. `loadURL` replaces that owned request. Parsing
  and document callbacks run on the owner thread.
- `third_party/maplibre-native/platform/default/src/mln/storage/file_source_request.cpp`:
  destroying a request runs its cancellation callback and closes its actor
  mailbox. An old response queued before replacement cannot invoke the old
  document callback after replacement.
- `src/resources/custom_resource_provider.cpp`: request destruction marks it
  cancelled and invokes the registered cancellation callback. Compose already
  connects that callback to the provider coroutine in the parent PR.

[FFI #407](https://github.com/maplibre/maplibre-native-ffi/issues/407) remains
open: style events have no generation ID. This design relies on owner-thread
ordering and native request retirement, not on matching URLs, inspecting current
style JSON, or guessing which event is newest. Changing styles directly through
a borrowed raw native handle bypasses Compose's style ownership contract.

## Lifecycle and snapshots

The lifecycle authority still decides whether to accept every style callback.
Closing or detaching does not install another engine or transfer callbacks to a
new owner. Rendering retains the previous complete frame until the replacement
style and application content are ready.

Snapshot capture uses a separate map and FIFO worker. It captures a frozen style
request and does not share the interactive session's pending style. Keep that
policy and its cancellation/cleanup tests; superseding the interactive map must
not cancel or retarget an already accepted snapshot.

## Validation plan

Use a provider that suspends its first URI document without a timeout. Replace
it and assert both a loaded new style and cancellation of the old provider.
First run that regression against the parent implementation to prove
sensitivity. Cover A/B/C replacement, queued old success and failure events,
late provider responses, callback reentrancy, failure recovery, and teardown.
Run native map runtime tests and static checks; host tests alone are not runtime
evidence.

Estimated implementation and review effort remains 1–2 engineering days. Device
availability can add calendar time. musl Linux is out of scope.
