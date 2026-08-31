# 12: Close composition before resetForTest

**What to build:** `runFfiComposeUiTest` disposes map content before
`MlnFfiApplication.resetForTest()`. Each live Compose class creates its cache
file per method. `resetForTest()` must not run against a surface that is still
drawing.

**Blocked by:** 11

**Type:** task

**Status:** resolved

API 24 aborted during
`MlnFfiMapCompositionTest.a_layer_removed_and_re_added_comes_back` with no
assertion. The method had already waited for the layer. The process died on
teardown. `runFfiComposeUiTest` always calls `resetForTest()` in `finally` while
the map is still composed.

- [x] After the test body, `setContent {}` disposes the map. Then
      `resetForTest()` runs.
- [x] Composition, style-switch, layer-click, and desktop host tests create a
      cache file in `@BeforeTest` and delete it in `@AfterTest`.
- [x] Do not retry the layer-toggle case in the test body.

## Test ledger

- [x] `a_layer_removed_and_re_added_comes_back` still asserts the native layer
      id after remove and re-add.
- [x] A later method in the same class does not reuse a deleted cache path.

## Answer

`runFfiComposeUiTest` calls `disposeFfiTestContent()` before
`ProcessNativeMapRuntime.resetForTest()` and `MlnFfiApplication.resetForTest()`.
`TestMap` remembers `StyleComposition` without keying on the composable lambda,
so a parent recomposition does not tear layers down. Cache files are created in
`@BeforeTest`. The layer-toggle case is not retried.
