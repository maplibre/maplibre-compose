# Committed style owner

Implementation revision, 2026-09-20, PR #1474. Supersedes the shared image cache
in the earlier design. Based on main `16e5f16891489d81d36205d13d5b3a214ef7a58f`.

## What was wrong

Image preparation had several owners: speculative composition created requests,
property effects prepared them, the committed tree retained them, and a shared
cache assigned IDs. A lock could protect a map operation but could not establish
whether a completed request still belonged to the current composition. Fixing
individual interleavings kept adding machinery around that split ownership.

## Model

```text
Compose evaluates descriptions
  -> applier commits StyleDeclaration
  -> StyleCompositionOwner resolves image properties
  -> DesiredStyleRevision
  -> MapStyleAuthority and StyleReconciler
```

Composition only creates descriptions. Reading bitmap pixels and rendering
painters begin after commit. One coroutine owns the required requests, their
jobs, image IDs, and the previous resolved properties. Painter workers return
results through a channel; they never mutate those records. A completion is
accepted only for the same active request entry. Removing and then reintroducing
the same painter cannot make an older completion current again.

Requests share preparation across properties and layers when their painter and
rendering environment match. Identical prepared pixels and image options share
one installed image. Sharing derives from current requests and the previous
resolved revision; there is no independently managed cache or reference count.

A new property waits for its painters. A replacement retains the previous whole
property until all its painters are ready. Sources, other properties, and layer
removals continue to apply. Removing the property also releases its retained
images. Removing the final request cancels preparation. Closing the owner
cancels its child work and prevents subsequent publication.

Live maps and snapshots use the same owner. Snapshots wait for a revision with
no pending images. Live maps apply partial readiness as described above. Source
preparation remains backend work; it is not routed through image preparation.

## Boundaries that remain

Each evaluator belongs to one loaded style. Style-generation validation and
imperative mutation reservations remain at MapStyleAuthority. The reconciler
still owns installed-resource ordering and mutation bookkeeping. The applier
commit is the only declaration publication boundary; image completion does not
require another Compose frame.

The loaded engine and its physical presentation are separate lifetimes. A
startup trace demonstrated that the first declaration could arrive after style
load but before physical presentation publication. Looking up the adapter
through the physical attachment dropped that declaration. Reconciliation now
uses the selected engine adapter. It does not add a replay effect or change
rendering.

## Validation evidence

- Owner tests cover shared preparation, equal-pixel sharing, pending replacement
  continuity with independent source updates/removals, and late completion after
  removal, reinsertion, and disposal.
- Composition tests exercise actual abandoned evaluation and verify that it does
  not read bitmap pixels. Existing layer tests exercise real painter rendering.
- The engine-before-presentation regression failed before the adapter lookup
  change and passed after it. The full desktop suite passed after that fix.
- Android host tests passed. Static checks passed.
- Browser validation is not uniformly green: Firefox completed the full suite,
  while Chrome timed out in imperative image tests that passed in isolation. An
  isolated Firefox resolver test also exposed an asynchronous readiness race.
  These observations do not establish that the failures predate this branch.

No Pixel measurements, app deployments, or rendering-scheduling changes were
made. The implementation adds explicit declaration/resolution types and tests;
it is not a net line-count reduction. The reduction is in concurrent ownership
paths.
