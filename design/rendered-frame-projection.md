# Rendered-frame projection

## Contract and scope

Desktop overlays must use the transform that produced the displayed texture. FFI
0.202609.3 provides `RenderSessionHandle.createProjection()`: acquire on the
renderer thread immediately after `renderUpdate()` returns `RENDERED`, before
another render or target change. The returned handle is independent, usable on
any thread, and owned until explicitly closed. It does not replace GPU fences.

Reuse #1417 and its measurement-time preparation. The surface owns a completed
presentation: target, frozen projection, and camera anchor. It publishes the
projection with the actual destination and density before overlay placement.
Skipped and capped frames retain this pair. Resize can translate an older image;
conversion applies that same translation and density. The host retains the old
target until a newer completed target is drawn. A new target invalidates FFI's
session snapshot, but not the independently acquired projection.

## Ownership and API

Keep this internal. `MlnFfiMapFrameProjection` is owned by the surface, not by
Compose snapshot state. A revision observable invalidates placement; a lock
protects the current handle and conversions so old Compose snapshots cannot
resurrect closed native resources. Replace or discard the completed presentation
on success, failure, recovery, or disposal, closing its projection exactly once.
A failure while completing GPU access must also close the unpublished candidate.

Live viewport observations, camera commands, and map queries keep the live-map
projection where appropriate. Desktop screen conversion follows the presented
image while one is available. No public snapshot or compatibility API is added.

Android uses a dedicated renderer and an independently presented surface buffer
queue. iOS uses a Metal surface driven by its display controller. Merely copying
a projection after those render calls would not synchronize their presentation
with Compose overlays. Preserve those paths; this change establishes desktop
texture/overlay synchronization, including the native macOS desktop host, not
atomic mobile surface presentation.

## Integration and validation

Merge the existing draft head into the FFI upgrade branch, preserving ancestry.
Remove the temporary composite dependency and unreleased-library instructions;
all validation must use the published dependency. Keep #1417 draft and stack it
on #1429 after verifying there is no active owner of its head branch.

Run desktop Metal tests, shared Android host tests, and static checks through
mise, serializing Gradle. Retain representative coverage for frame retention,
resize translation, camera movement, disposal, and publication before placement.
Add failure cleanup coverage. Report other GPU backends and mobile presentation
as unverified rather than infer their runtime behavior from compilation.
