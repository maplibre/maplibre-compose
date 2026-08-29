# 08: Separate desktop presentation hosts from runtimes

**What to build:** Make the desktop presentation host a window-scoped rendering
resource with presentation-specific naming. Keep runtime configuration,
logical-map ownership, and offline services outside that host.

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] The desktop host API uses ComposeMapPresentationHost terminology.
- [ ] The presentation host contains only window and GPU presentation resources.
- [ ] Cache, resource, HTTP, and offline configuration belongs to MapRuntime.
- [ ] Replacing a presentation host replaces the presentation without replacing
      MapRuntime or MapState.
- [ ] Backend or scale-factor incompatibility follows the native engine
      replacement policy without transferring runtime ownership to the host.
- [ ] Every desktop render backend and application host uses the renamed
      contract.
- [ ] Desktop tests prove host, runtime, and logical-map lifetime independence.

## Test ledger

- Rewrite `ComposeMapHostBridgeLifecycleTest.kt` and
  `RenderBackendNegotiationTest.kt` around presentation-host ownership and
  compatibility.
- Retain the Metal, Vulkan/OpenGL, and Direct3D contract tests only where they
  verify a distinct backend boundary.
- Run `mise run test:desktop` for the default backend and every backend changed
  by the implementation.
