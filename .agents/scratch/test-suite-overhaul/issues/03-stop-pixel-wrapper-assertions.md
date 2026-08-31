# 03: Stop using pixels for wrapper contracts

**What to build:** A test that proves a wrapper invariant (stored feature state,
style identity, attach count, camera) must assert that value. A pixel belongs
only in a layer-5 case whose bug is color or framebuffer ownership.

**Blocked by:** 01

**Type:** task

**Status:** claimed

- [x] `MlnFfiSurfaceLossTest.feature_state_accepts_mutations_without_a_surface_and_replays_into_its_replacement`
      asserts `style.featureState` and `pumpUntilRendered` across loss and
      restore.
- [ ] `FeatureStateTest` stays as the one live pixel proof that feature state
      changes the style.
- [ ] `GeoJsonSourceUpdateTest` asserts source data or query results for the
      wrapper path; keep at most one pixel case if color is the bug.
- [ ] `CustomVectorSourceTest` and `ImageSourceDrawTest` keep a pixel only if no
      query or callback can prove the draw.
- [ ] `MlnFfiMapPixelTest` remains layer 5 and runs on one native backend.

## Test ledger

- Surface-loss feature state: stored keys survive detach; reset clears them; a
  restored surface renders.
- `FeatureStateTest`: red/blue circle still proves the paint expression.
