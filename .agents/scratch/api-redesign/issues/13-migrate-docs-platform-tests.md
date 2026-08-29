# 13: Migrate documentation and platform tests

**What to build:** Update public documentation, compiled snippets, and
real-engine tests to describe and exercise only the new API. Keep shared
lifecycle semantics in common fake-adapter tests.

**Blocked by:** 10, 11

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Documentation and compiled snippets show only MapRuntime, MapState,
      MapPresentation, and StyleComposition.
- [ ] This ticket solely owns `docs/` and every
      `demo-app/common/src/*/kotlin/org/maplibre/compose/docsnippets` package.
- [ ] Native tests cover compatible retention, incompatible engine replacement,
      and durable-state replay through the public API.
- [ ] Browser tests cover destruction, recreation, replay, and readiness through
      the public API.
- [ ] Desktop tests cover presentation-host replacement independently from
      runtime and logical-map lifetime.
- [ ] Platform tests that duplicate common behavior without testing an engine
      boundary are deleted.
- [ ] Documentation builds and the focused native, browser, and desktop tests
      pass.
- [ ] The PR contains a final table classifying every affected platform test as
      retained, rewritten, consolidated, or deleted.

## Test ledger

- Treat every current `liveMapTest` and `jsTest` map/style/source test as an
  explicit keep, rewrite, consolidate, or delete decision in the PR.
- Limit test changes to `androidDeviceTest`, `androidJvmTest`, `iosTest`,
  `jvmTest`, `jsTest`, `liveMapTest`, and `maplibreNativeTest`. Common tests
  remain owned by tickets 02, 06, 07, and 10.
- Consolidate shared lifecycle cases into the common fake-adapter suite; retain
  platform tests only for native identity, GL JS recreation, rendering, input,
  and host integration boundaries.
- Run `mise run build:docs`, `mise run test:android`,
  `mise run test:android:device`, `mise run test:desktop`, `mise run test:ios`,
  and `mise run test:js`.
