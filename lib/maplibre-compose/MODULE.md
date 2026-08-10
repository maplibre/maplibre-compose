# Module maplibre-compose

The primary entry point for MapLibre Compose.

# Package org.maplibre.compose.map

Core package containing the primary map composable and related components.

# Package org.maplibre.compose.browser

Process-wide setup for the browser platform. `MapLibre.initialize()` has to run
before Compose starts, because the map is drawn inside the Compose scene as a
GPU texture and creating one needs the graphics context Compose renders with.

It is composited rather than mounted as a DOM canvas beside Compose because such
a canvas covers, or hides under, the whole Compose surface — map content and
Compose content could never be interleaved.

Four things the browser does not have, because MapLibre GL JS does not:
`ComputedSource`, offline packs, ornaments, and the default location and
orientation providers. Each fails with that explanation rather than doing
nothing quietly.

# Package org.maplibre.compose.camera

Camera controls and positioning utilities for the map view.

# Package org.maplibre.compose.offline

Functionality for managing offline map data and caching.

# Package org.maplibre.compose.layers

Composables and related utilities to add layers to the map.

# Package org.maplibre.compose.sources

Composables and related utilities to add sources to the map.

# Package org.maplibre.compose.expressions.ast

The abstract syntax tree (AST) for the expression language.

# Package org.maplibre.compose.expressions.dsl

The Kotlin DSL for creating MapLibre expressions. This is the primary API you'll
be using to create expressions.

# Package org.maplibre.compose.expressions.value

The interfaces and enums defining the type system for MapLibre expressions.
