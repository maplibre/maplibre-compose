# Expression compiler plugin prototype

This is a throwaway exploration. It should compile and run, but it is not a
merge candidate. The goal is to see whether users can write MapLibre expressions
as ordinary Kotlin, with `if` / `when` and operators, instead of the `const` /
`switch` / `gt` DSL.

The idea comes from
[kotlin-expr-tree-kcp](https://github.com/Kronos-orm/kotlin-expr-tree-kcp): a K2
plugin captures a typed Kotlin lambda. This prototype lowers that lambda
straight to the existing `Expression` AST, so layer properties and JSON encoding
stay the same.

## What went well

- Keeping the current AST as the lowering target meant layers, compile context
  (images, text units), and `toStyleJson()` did not need a rewrite.
- Typing `expr { }` as real Kotlin (`Boolean`, `Color`, `Dp`, `Double`) is what
  makes `if (mag > 5) Color.Red else Color.Yellow` type-check. That is the whole
  point of a compiler plugin: `if` needs a `Boolean`, so the lambda cannot use
  `Expression<BooleanValue>` as the user-facing type.
- `ExprEmit` is a small, overload-free runtime API. The IR phase only has to
  emit `lit` / `op` / `interpolate` / `step` / `match`. That avoided picking
  among the DSL's many overloads from IR. Vararg `Expression<*>` failed at
  runtime (`Object[]` cannot cast to `Expression[]`); list parameters work.
- Reusing Kotlin's own functions covers a large part of the style spec: `+` /
  `*` / `>`, `kotlin.math.sqrt`, `String.uppercase()`, `List.contains`.
- Local `val` inlining and captured outer values (`val threshold = 5.0`) fall
  out of IR `IrGetValue` handling.
- Subject `when (feature.string("kind")) { "park" -> ... }` can become `match`
  when every branch is equality against a composition-time label. Comparison
  `when { mag >= 6 -> ... }` stays `case`.
- A GeoJSON literal is a bare JSON object. `within` / `distance` need that
  shape; wrapping it in `["literal", ...]` would be the wrong type.
- Compile-time const-able checking is possible from IR types. The plugin reports
  through `MessageCollector` and keeps walking so one `expr` can produce several
  errors.

## What did not go well

- Kotlin compiler plugin APIs are unstable and version-locked to
  `kotlin-compiler-embeddable` 2.4.10. A FIR checker plus registry (the kcp
  design) would give better diagnostics; this prototype is IR-only, so
  unsupported constructs fail while generating IR, not during analysis. The IDE
  still type-checks the stubs and does not show the rewritten tree.
- Primitive comparisons lower in several IR shapes (`greater`, `compareTo`,
  `IrWhen` with `ANDAND` / `OROR`). The visitor has to special-case each.
- Interface members declared as `fun Any?.asNumber()` are awkward in IR
  (dispatch receiver is the scope, extension receiver is the value).
- Reified `asEnum<SymbolAnchor>()` cannot be an interface member. The prototype
  takes `List<String>` of style-spec names. Callers write
  `SymbolAnchor.entries.map { it.literal.value }`.
- `Color` is a value class, so `vararg fallbacks: Color` is not usable on
  common. `asColor` / `convertToColor` take `Any?` fallbacks instead.
- Compose's `Int.dp` / `Double.sp` / `kotlin.time`'s `Double.seconds` win
  overload resolution over `ExprScope` extensions on `Number`. The plugin has to
  recognize those getters in IR. Map-time `.seconds` becomes `* 1000`;
  composition-time `.seconds` freezes a `Duration`.
- Native compilations do not share `AbstractKotlinCompile.pluginClasspath`. The
  Gradle helper passes `-Xplugin` there.
- `Options.build` is internal, so formatted spans and collators go through
  `ExprEmit` helpers instead of the public DSL.
- A real replacement would hide the old DSL. This prototype cannot: layer
  defaults and existing tests still call `const`, and `allWarningsAsErrors`
  would turn a package-wide `@Deprecated` into a red build.

## Limitations the hard cases showed

These are engine or Kotlin constraints, not missing visitor branches.

1. **MapLibre has no array constructor for dynamic components.**
   `Offset(feature.number("x"), feature.number("y"))`, `padding(...)` from
   feature numbers, and `listOf(feature.string("a"))` cannot become style JSON.
   `offset` / `padding` / `listOf` are composition-time only. The plugin rejects
   map-evaluated arguments at compile time. `asOffset()` only _asserts_ that a
   value is already a two-number array.
2. **Text-unit offsets are the same hole.** `offset(12.sp, 4.sp)` works.
   `offset(feature.number("x").sp, feature.number("y").em)` does not: the AST
   stores two `TextUnit` values, not expressions.
3. **`image(bitmap)` / `image(painter)` options are composition-time.** Size,
   SDF, stretch, alpha, and `ColorFilter` are inputs to bitmap registration, not
   map operators. A style-image _name_ can be map-evaluated:
   `image(feature.string("icon"))`.
4. **User functions cannot take map-evaluated arguments.**
   `paint(feature.number("mag"))` would have to run Kotlin per feature. If every
   argument is composition-time and the return type is const-able, `themeRed()`
   becomes `const(themeRed())`.
5. **`kotlin.math.PI` is a `Double`, not the `pi` operator.** Use `pi` / `e` /
   `ln2` on the scope when the JSON should contain `["pi"]`.
6. **The old `asPadding()` DSL asserts length 2.** That looks like a spec bug
   (padding is four numbers). The plugin emits length 4.
7. **`var`, loops, `try`, and assignment** have no style-spec form. The plugin
   reports them as errors instead of aborting IR generation.
8. **`null` is typed as `Nothing?`.** The first const-able check rejected it, so
   `feature["kind"] != null` failed to compile. `Nothing` has to be treated as
   `nil`.
9. **Do not match helpers by package prefix.** Tests live in
   `org.maplibre.compose.expressions.kotlin`, so a prefix match treated
   `themeRed()` as an expr helper. Only `ExprScope` / `FeatureExpr` owners
   count.
10. **Kotlin/JS boolean literals are two separate holes.** `is Boolean` does not
    match a primitive JS `boolean`, so `lit(Any?)` must match `true` / `false`
    by equality. Interning `BooleanLiteral.True`/`False` on the companion also
    fails: constructing those instances re-enters the companion before the
    fields are assigned, and `of(true)` returns `undefined`. Do not intern them.
    Boolean IR constants now call typed `litBoolean(Boolean)`, which goes
    through `const(Boolean)` and never boxes as `Any?`.
11. **Kotlin/JS numbers are one `typeof === 'number'`.** `is Int` / `is Float` /
    `is Double` all match, so `lit(2.5)` used to take the Int branch.
    `IntCache[2.5]` is a hole (`array[2.5]` is `undefined`) and `compile()` then
    throws. `lit(Any?)` now has one `Number` path: whole values become `Int`
    literals, the rest `Float`.
12. **Dokka `failOnWarning` rejects an unresolved `[EnumValue]` KDoc link.**
    `ExprScope` does not import that type, and `ExprScopeStubs.asEnum` inherits
    the same comment. A fully qualified destination still warned. Write the type
    name in prose instead of a link.

Const-able types: Boolean, Number, String, Color, Dp, Offset, DpOffset,
DpPadding, TextUnit, Duration, ProjectionTransition, EnumValue, ImageBitmap,
Painter, GeoJSON, lists of those, and `Expression`. Anything else is a compile
error, including a capture of `MapState` or a lambda.

## Major decisions

1. **IR-only plugin, not FIR extract + generic tree.** kcp builds a portable
   `ExprTree` ADT. MapLibre already has an AST that serializes to style JSON, so
   a second tree would be waste. The plugin walks the lambda IR and emits
   `ExprEmit` calls.
2. **`expr { }` returns `Expression<T>`, layers stay unchanged.** The user API
   is the lambda. The engine API is still the sealed `Expression` type.
3. **One generic `expr`.** Per-return-type overloads clashed on the JVM and
   broke receiver resolution (`feature` was unresolved).
   `fun <T : ExpressionValue> expr(block: ExprScope.() -> Any?)` lets the layer
   property infer `T` (`color = expr { Color.Red }`).
4. **Helpers stay on `ExprScope` for operations Kotlin does not have:**
   `interpolate`, `step`, `match`, `feature`, `zoom`, `format`, `image`,
   `collator`, `bind` (MapLibre `let`).
5. **Two evaluation times, checked in IR.** Map-evaluated: feature/zoom/helpers
   and operators over those. Composition-time `lit`: a const, an outer capture,
   or a Kotlin call whose arguments are all composition-time and whose return
   type is const-able.
6. **Old DSL stays public as the implementation and escape hatch.** A later
   design could mark it `internal` once the plugin is the only consumer.
7. **Default CI tiers only.** This is shared Kotlin plus a JVM compiler plugin.
   It does not change ABI, pointer layout, or native loading.

## Mapping (Kotlin → MapLibre)

| Kotlin in `expr { }`          | Style spec                    |
| ----------------------------- | ----------------------------- |
| `if` / comparison `when`      | `case`                        |
| subject `when` / `match()`    | `match`                       |
| `&&` / `\|\|` / `!`           | `all` / `any` / `!`           |
| `?:`                          | `coalesce`                    |
| `+` `-` `*` `/` `%` (numbers) | same                          |
| `+` (strings) / templates     | `concat`                      |
| `>` `<` `==`                  | same                          |
| `eq(a, b, collator)`          | `==` with collator            |
| `kotlin.math.*`               | same names (`pow` → `^`)      |
| `ln2` / `pi` / `e`            | `ln2` / `pi` / `e`            |
| `String.uppercase()`          | `upcase`                      |
| `feature["key"]`              | `get`                         |
| `feature.number("key")`       | `number` + `get`              |
| `feature.properties()[k]`     | `get` on the properties map   |
| `zoom`                        | `zoom`                        |
| `interpolate` / `step`        | same                          |
| `n.seconds` (map-time)        | `*` 1000                      |
| `n.sp` / `n.em` (map-time)    | `TextUnitCalculation`         |
| `image("marker")`             | `["image", "marker"]`         |
| `image(bitmap, isSdf = true)` | one `image` + `BitmapLiteral` |
| `feature.within(point)`       | `within` + GeoJSON object     |
| captured const-able value     | `literal` via `ExprEmit.lit`  |

Rejected in the lambda: `var`, loops, `try`, user functions on feature data,
dynamic `listOf` / `Offset` construction, side effects.

## How to apply

The consuming Gradle project calls `applyMapLibreExprCompilerPlugin()` from
`buildSrc`. That puts `:lib:maplibre-compose-expr-compiler` on the Kotlin
`pluginClasspath`. Without the plugin, `expr { }` still type-checks and then
throws at runtime.
