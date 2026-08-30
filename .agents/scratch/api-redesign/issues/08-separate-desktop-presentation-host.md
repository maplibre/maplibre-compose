# 08: Separate desktop presentation hosts from runtimes

**What to build:** Make the desktop presentation host a window-scoped rendering
resource with presentation-specific naming. Keep runtime configuration,
logical-map ownership, and offline services outside that host.

**Blocked by:** 04

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] The desktop host API uses ComposeMapPresentationHost terminology.
- [x] The presentation host contains only window and GPU presentation resources.
- [x] Cache, resource, HTTP, and offline configuration belongs to MapRuntime.
- [x] Replacing a presentation host replaces the presentation without replacing
      MapRuntime or MapState.
- [x] Backend or scale-factor incompatibility follows the native engine
      replacement policy without transferring runtime ownership to the host.
- [x] Every desktop render backend and application host uses the renamed
      contract.
- [x] Desktop tests prove host, runtime, and logical-map lifetime independence.

## Test ledger

- Rewrite `ComposeMapHostBridgeLifecycleTest.kt` and
  `RenderBackendNegotiationTest.kt` around presentation-host ownership and
  compatibility.
- Retain the Metal, Vulkan/OpenGL, and Direct3D contract tests only where they
  verify a distinct backend boundary.
- Run `mise run test:desktop` for the default backend and every backend changed
  by the implementation.

## Answer

The desktop API now names its window-scoped rendering resource
`ComposeMapPresentationHost`. The AWT, GLFW, and Nucleus Tao hosts use the same
presentation-specific contract. Runtime configuration and logical-map ownership
remain on `MapRuntime` and `MapState`.

Changing the presentation host ends the current render lease and creates a new
`MapPresentation`. A compatible host retains the native engine map. The existing
backend and scale-factor compatibility key replaces an incompatible engine and
replays the durable map state.

The Desktop presentation-host lifetime test covers host replacement, engine
retention, and runtime and logical-map independence. The bridge and backend
tests retain distinct Metal, Vulkan/OpenGL, and Direct3D boundaries.
