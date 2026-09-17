# Global state

Global state supplies values shared by style expressions without rewriting each
layer. This design targets GL JS 6.9.1 and Native FFI 0.202609.3.

## Contract

- `globalState(key: String): Expression<AnyValue?>` emits `global-state` with a
  literal key. Existing assertions and conversions (`asBoolean`, `asNumber`,
  `asString`, `convertToColor`) give the result a type and optional fallback. A
  missing key evaluates to null. The key cannot itself be an expression.
- `mapState.style.globalState` exposes `setProperty(name, JsonElement)`,
  `resetProperty(name)`, and suspending `get(): JsonObject?`. Values are JSON
  data, including arrays and objects, never expression syntax.
- The base style owns defaults in its root `state` object, with entries shaped
  as `{"name":{"default":value}}`. `JsonNull` and `resetProperty` restore that
  default, or null for an undeclared key. Readback includes effective defaults.
- Writes require a ready loaded style. They use the existing guarded engine
  dispatch: Native posts to its owner thread; JS executes immediately. Readback
  awaits earlier Native writes, returns an independent snapshot, and returns
  null when no style is ready or its generation changes during the read.
- A new base style replaces all global state. Values are not saved, replayed, or
  shared between maps. Applications that own persistent preferences must reapply
  them after each successful style load. Writes queued for an old generation
  cannot reach its replacement.

## Alternatives

A separate remembered global-state object with automatic replay would add a
second owner for style data and obscure root defaults on reload. A typed key
registry would claim static knowledge of externally supplied JSON styles and
still need runtime validation. Generic unchecked casts would hide mismatches. A
new typed style-document builder is unnecessary: `BaseStyle.Json` already
accepts the complete style specification. A whole-state replace operation would
need extra reset and merge rules that neither backend's public setter provides.

A suspending setter could acknowledge engine completion, but would require a
coroutine for UI event callbacks and introduce cancellation after dispatch.
These per-property changes are commands: dispatch them without suspension and
use readback when completion matters. They are not transactions; engine
rejections use the map logger.

The chosen API keeps runtime data on the loaded style and uses the expression
DSL's existing runtime assertions. This choice is based on ownership and
usability, not backwards compatibility. No aliases or compatibility overloads
are needed.

## Backend evidence

- [GL JS map API at v6.9.1](https://github.com/maplibre/maplibre-gl-js/blob/v6.9.1/src/ui/map.ts)
  exposes `setGlobalStateProperty` and `getGlobalState`.
- [GL JS style implementation](https://github.com/maplibre/maplibre-gl-js/blob/v6.9.1/src/style/style.ts)
  loads root defaults, resets on null, and invalidates dependent paint, layout,
  filter, and visibility evaluation. Its getter returns live storage, so the
  binding must convert it to JSON. Compose already uses `diff = false` when
  replacing a JS style, avoiding the engine's incremental-state merge behavior.
- [Style-spec parser at v26.4.2](https://github.com/maplibre/maplibre-style-spec/blob/v26.4.2/src/expression/definitions/global_state.ts)
  requires a literal string key and returns an untyped, nullable value.
- [FFI #717](https://github.com/maplibre/maplibre-native-ffi/pull/717) provides
  JSON setters/readback and Native invalidation for dependent paint, filters,
  layout, and color ramps. Kotlin's `MapHandle` enforces owner-thread access.

## Validation plan

Use common expression serialization coverage and shared live-map tests for root
defaults, JSON values, resets, readiness, reload isolation, and rendered changes
to paint, filters, layout, and a line-gradient color ramp. Run desktop and JS
tests serially through mise, style parity, static checks, and compile the
documentation snippet. Keep Android/iOS and other desktop architectures to
normal draft/ready CI tiers: this change uses existing FFI methods and changes
no ABI or artifact selection.
