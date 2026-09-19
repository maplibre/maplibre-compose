# Module maplibre-compose-editing

Feature editing for MapLibre Compose: a map-free editor state, input routing on
the map surface, built-in select and draw tools, and layers that render the
edited features.

Value types (`VertexRef`, `EditorHandle`, `EditorDraft`, `EditorPointer`,
`HandleHit`, `FeatureHit` and every `EditorEvent`) are data classes; wrappers
derive events with `copy`, never with constructors. `EditorColors` follows the
Compose `ButtonColors` convention (a class with `copy`, `equals` and `hashCode`)
because its defaults derive from `accent`.

The module follows the repository's pre-1.0 policy: data-class constructors may
gain trailing parameters with defaults in minor releases, `DrawShape` and
`EditorEvent` may gain cases, and an exhaustive `when` over them needs an `else`
branch.

# Package org.maplibre.compose.editing

Editor state, tools, events, input modifier and rendering layers.
