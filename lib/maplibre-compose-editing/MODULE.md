# Module maplibre-compose-editing

Feature editing for MapLibre Compose. `FeatureEditorState` holds the features,
tool, selection, draft and undo history without a map reference;
`Modifier.featureEditor` routes input on the map surface to the tool;
`FeatureEditorLayers` draws the state as map layers; `SelectTool` and `DrawTool`
are the built-in tools, and `EditorTool` is the extension point. The
[Edit features](https://maplibre.org/maplibre-compose/editing/) guide shows the
integration patterns.

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
