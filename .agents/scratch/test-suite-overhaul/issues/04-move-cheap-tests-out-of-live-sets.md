# 04: Move cheap tests out of live source sets

**What to build:** Relocate tests that do not need a MapLibre runtime or a GPU
out of `liveMapTest` and `maplibreNativeTest` into `commonTest` or a native-only
unit set that android host can run when the types allow it.

**Blocked by:** 01

**Type:** task

**Status:** ready-for-agent

Candidates that create no map today:

- `JsonConversionsTest`, `MlnFfiConversionsTest` (native types: stay native, but
  not on the GPU path)
- `OfflineProgressMappingTest`
- `MlnFfiResourceProviderTest`, `MlnFfiResourceRequestTest`
- `MlnFfiTileRequestCoordinatorTest`
- `FileUrlTest`, `ImagePremultiplyTest`
- `MapOverlayTest` and `MaplibreLogoTest` already use `mapRuntimeForTest`; they
  need a Compose UI host, not a live map. They belong next to other Compose-only
  tests, gated by `supportsComposeRuntimeTests`.

Do not move a class that calls `BridgeMapFixture.create`, `createMapFixture`, or
`runFfiComposeUiTest`.

## Test ledger

- After a move, `mise run test:android` still runs the case.
- `mise run test:desktop` does not create a GPU context for that class.
