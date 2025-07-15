# Adding data to the map

## Sources and layers

As covered in [Styling the map](styling.md), the data displayed on the map is
defined by the style, which is provided as a URI to a JSON object. However, this
library provides a way to add additional data to the map at runtime.

A map style primarily consists of sources and layers. Sources contain the data
included on the map, and layers configure how that data is displayed. With
MapLibre Compose, you can dynamically configure sources and layers with
`@Composable` functions:

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Layers.kt:simple"
```

The above example shows how to add a layer referring to a source from the base
style, but you can also declare new sources:

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Layers.kt:amtrak-1"
```

The full breadth of layer styling options available is out of scope for this
guide; see the [MapLibre Style Specification][spec-layers] for more information.

## Expressions

MapLibre styles support expressions, which define a formula for computing the
value of a property at runtime. The [expressions specification][spec-layers] is
based on JSON, but this library provides a Kotlin DSL for expressing these
formulas with some type safety. Where suitable, Compose types like `Dp`,
`Color`, and `DpOffset` are used instead of their corresponding raw JSON-like
types. When passing a constant value instead of a formula, wrap it in `const()`
to turn it into an expression.

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Layers.kt:amtrak-2"
```

## Anchoring layers

The default behavior for layers defined by Composable functions is to place them
at the top of the map, above the base style layers. However, you can insert them
at other positions in the stack of layers with `Anchor`:

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Layers.kt:anchors"
```

Anchors to insert layers at the `Bottom`, `Top`, `Above` a base layer, `Below` a
base layer, or `Replace` a base layer are provided.

## Interacting with layers

Layer composables provide click listeners, similar to the map itself. You can
listen for clicks on a layer and consume or pass those click events:

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Layers.kt:interaction"
```

Click listeners are called on the map first, then in layer order from the top to
the bottom of the map. The first listener to consume the event will prevent it
from propagating to subsequent listeners.

[spec-layers]: https://maplibre.org/maplibre-style-spec/layers/
[spec-expressions]: https://maplibre.org/maplibre-style-spec/expressions/
