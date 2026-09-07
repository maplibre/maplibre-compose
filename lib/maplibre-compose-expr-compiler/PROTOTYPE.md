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

## What did not go well

- Kotlin compiler plugin APIs are unstable and version-locked to
  `kotlin-compiler-embeddable` 2.4.10. A FIR checker plus registry (the kcp
  design) would give better diagnostics; this prototype is IR-only, so
  unsupported constructs fail while generating IR, not during analysis.
- Primitive comparisons lower in several IR shapes (`greater`, `compareTo`,
  `IrWhen` with `ANDAND` / `OROR`). The visitor has to special-case each.
- `when` always becomes `case`, not `match`. That is correct but misses the more
  compact `match` encoding the old DSL used for label dispatch.
- Interface members declared as `fun Any?.asNumber()` are awkward in IR
  (dispatch receiver is the scope, extension receiver is the value).
- Native compilations do not share `AbstractKotlinCompile.pluginClasspath`. The
  Gradle helper passes `-Xplugin` there. IDE highlighting also will not show the
  rewritten tree.
- `Options.build` is internal, so formatted spans and collators go through
  `ExprEmit` helpers instead of the public DSL.
- A real replacement would hide the old DSL. This prototype cannot: layer
  defaults and existing tests still call `const`, and `allWarningsAsErrors`
  would turn a package-wide `@Deprecated` into a red build.

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
   `interpolate`, `step`, `feature`, `zoom`, `format`, `image`, `collator`,
   `bind` (MapLibre `let`).
5. **Old DSL stays public as the implementation and escape hatch.** A later
   design could mark it `internal` once the plugin is the only consumer.
6. **Default CI tiers only.** This is shared Kotlin plus a JVM compiler plugin.
   It does not change ABI, pointer layout, or native loading.

## Mapping (Kotlin → MapLibre)

| Kotlin in `expr { }`          | Style spec                   |
| ----------------------------- | ---------------------------- |
| `if` / `when`                 | `case`                       |
| `&&` / `\|\|` / `!`           | `all` / `any` / `!`          |
| `?:`                          | `coalesce`                   |
| `+` `-` `*` `/` `%` (numbers) | same                         |
| `+` (strings) / templates     | `concat`                     |
| `>` `<` `==`                  | same                         |
| `kotlin.math.*`               | same names (`pow` → `^`)     |
| `String.uppercase()`          | `upcase`                     |
| `feature["key"]`              | `get`                        |
| `feature.number("key")`       | `number` + `get`             |
| `zoom`                        | `zoom`                       |
| `interpolate` / `step`        | same                         |
| captured outer value          | `literal` via `ExprEmit.lit` |

Rejected in the lambda: `var`, loops, `try`, user functions that are not
inlined, side effects. MapLibre expressions are pure JSON.

## How to apply

The consuming Gradle project calls `applyMapLibreExprCompilerPlugin()` from
`buildSrc`. That puts `:lib:maplibre-compose-expr-compiler` on the Kotlin
`pluginClasspath`. Without the plugin, `expr { }` still type-checks and then
throws at runtime.
