# 08: Separate desktop presentation hosts from runtimes

**What to build:** Make the desktop presentation host a window-scoped rendering
resource with presentation-specific naming. Keep runtime configuration,
logical-map ownership, and offline services outside that host.

**Blocked by:** 04: Retain native engine maps between presentations

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] The desktop host API uses ComposeMapPresentationHost terminology.
- [ ] The presentation host contains only window and GPU presentation resources.
- [ ] Cache, resource, HTTP, and offline configuration belongs to MapRuntime.
- [ ] Replacing a presentation host replaces the presentation without replacing
      MapRuntime or MapState.
- [ ] Every desktop render backend and application host uses the renamed
      contract.
- [ ] Desktop tests prove host, runtime, and logical-map lifetime independence.
